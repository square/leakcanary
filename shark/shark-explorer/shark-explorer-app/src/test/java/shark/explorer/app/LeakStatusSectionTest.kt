package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.explorer.Adb
import shark.explorer.AdbOutput
import shark.explorer.DeviceHeapDumps
import shark.explorer.LeakStatus
import shark.explorer.LeakStatusFile
import shark.explorer.LeakStatusOverride
import shark.explorer.LeakStatusOverrides
import shark.explorer.Place
import shark.explorer.hexObjectId
import shark.explorer.statusText

/**
 * Whether the object a tab is on is meant to be in memory, said at the top of the panel that says what the
 * object is, changed by hand from there, and what the answer marks on the chain beside it.
 *
 * What the statuses mean and how two of them disagree is `LeakStatusTest` and `HeapLeakStatusTest` in
 * `shark-explorer-core`, and where they are kept is `LeakStatusFileTest`. What is only true here is that the
 * panel says what the heap dump says, that changing one asks for the reason before it writes anything, that
 * a status which cannot be true alongside another is shown rather than settled quietly, and that the chain
 * says which reference the leak is.
 */
@OptIn(ExperimentalTestApi::class)
class LeakStatusSectionTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  /** Every UI test here records what Shark logged. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  /** Where the statuses of the heap dump under test are kept, which is this test's own directory. */
  private val statusesRoot by lazy { testFolder.newFolder("leak-statuses") }

  private lateinit var heapDump: LeakyChainHeapDump

  @Test fun `an object the inspectors say should be gone says so in the panel`() {
    explorerUiTest {
      openHeapDump { it.activityObjectId }

      onNodeWithText(STATUS_LABEL).assertIsDisplayed()
      onNode(shows(LeakStatus.LEAKING)).assertIsDisplayed()
      // And why, because a status is a conclusion and half of them are about another object. The reason as
      // the panel has it, which is what the chain beside it prefixes with the status.
      onNodeWithText(DESTROYED_REASON).assertIsDisplayed()
    }
  }

  /** Quietly, because most of a heap dump is this and a line that shouted it would be read by nobody. */
  @Test fun `an object nothing knows either way about says that`() {
    explorerUiTest {
      openHeapDump { it.holderObjectId }

      onNode(shows(LeakStatus.UNKNOWN)).assertIsDisplayed()
    }
  }

  /** There is nothing to inspect about the heap dump as a whole, and nothing to decide about it either. */
  @Test fun `the tab on the whole heap dump has no status`() {
    explorerUiTest {
      openHeapDump()

      onNodeWithText(EDIT_STATUS_GLYPH).assertDoesNotExist()
      onNode(shows(LeakStatus.UNKNOWN)).assertDoesNotExist()
    }
  }

  @Test fun `a status set by hand is what the panel says, and it is on disk`() {
    explorerUiTest {
      openHeapDump { it.activityObjectId }
      changeStatus()

      choose(LeakStatus.NOT_LEAKING)
      write(TYPED_REASON)
      set()

      waitUntilAtLeastOneExists(shows(LeakStatus.NOT_LEAKING), SAVE_TIMEOUT_MILLIS)
      // Marked as somebody's rather than the heap dump's, which is the difference between reading the dump
      // and reading a conclusion about it, and with what it overruled after it.
      onNodeWithText("$SET_BY_HAND$TYPED_REASON. Conflicts with $DESTROYED_REASON").assertIsDisplayed()
      waitUntil(timeoutMillis = SAVE_TIMEOUT_MILLIS) {
        statusFile().read()[heapDump.activityObjectId]?.status == LeakStatus.NOT_LEAKING
      }
      assertThat(statusFile().read()[heapDump.activityObjectId]!!.reason).isEqualTo(TYPED_REASON)
    }
  }

  /** The whole of why a status set by hand is worth keeping: without the why it is a colour somebody chose. */
  @Test fun `a status cannot be set without a reason`() {
    explorerUiTest {
      openHeapDump { it.activityObjectId }
      changeStatus()

      choose(LeakStatus.NOT_LEAKING)

      setButton().assertIsNotEnabled()
      write("because I read the code")
      setButton().assertIsEnabled()
    }
  }

  @Test fun `a status set by hand can be taken back off`() {
    explorerUiTest {
      openHeapDump { it.activityObjectId }
      changeStatus()
      choose(LeakStatus.NOT_LEAKING)
      write("this screen is deliberately kept")
      set()
      waitUntilAtLeastOneExists(shows(LeakStatus.NOT_LEAKING), SAVE_TIMEOUT_MILLIS)

      // Which is the one thing only the dialog of a status already set offers.
      changeStatus()
      onNode(hasText(CLEAR_STATUS) and isButton()).performClick()

      // And the heap dump says what it said about the object again.
      waitUntilAtLeastOneExists(shows(LeakStatus.LEAKING), SAVE_TIMEOUT_MILLIS)
      waitUntil(timeoutMillis = SAVE_TIMEOUT_MILLIS) { statusFile().read().isEmpty }
    }
  }

  @Test fun `a status that cannot be true alongside another is shown before anything is written`() {
    explorerUiTest {
      // Set in a run before this one: the holder above the activity is leaking, so everything it holds is.
      openHeapDump(setAlready = { holderIsLeaking() }) { it.activityObjectId }
      changeStatus()

      choose(LeakStatus.NOT_LEAKING)
      write("this screen is deliberately kept")
      set()

      // The one it disagrees with, by name, with what it was given as its reason: whoever is about to
      // overrule it is the only person who can weigh the two, and only if they can read it.
      waitUntilAtLeastOneExists(hasText("$HOLDER_NAME $CONFLICT_ABOVE"), SAVE_TIMEOUT_MILLIS)
      onNodeWithText("${LeakStatus.LEAKING.statusText}: $HOLDER_REASON").assertIsDisplayed()
      onNodeWithText("$CONFLICT_BECOMES ${LeakStatus.NOT_LEAKING.statusText}")
        .assertIsDisplayed()
      // And nothing written while the question is open, which is what makes undoing it free.
      assertThat(statusFile().read().all.map { it.status }).containsExactly(LeakStatus.LEAKING)
    }
  }

  @Test fun `keeping the new status flips every status that disagreed with it`() {
    explorerUiTest {
      openHeapDump(setAlready = { holderIsLeaking() }) { it.activityObjectId }
      changeStatus()
      choose(LeakStatus.NOT_LEAKING)
      write("this screen is deliberately kept")
      set()
      waitUntilAtLeastOneExists(hasText(SOLVE_CONFLICTS), SAVE_TIMEOUT_MILLIS)

      onNode(hasText(SOLVE_CONFLICTS) and isButton()).performClick()

      waitUntil(timeoutMillis = SAVE_TIMEOUT_MILLIS) {
        statusFile().read()[heapDump.activityObjectId] != null
      }
      val overrides = statusFile().read()
      assertThat(overrides[heapDump.activityObjectId]!!.status).isEqualTo(LeakStatus.NOT_LEAKING)
      val flipped = overrides[heapDump.holderObjectId]!!
      assertThat(flipped.status).isEqualTo(LeakStatus.NOT_LEAKING)
      // Flipped rather than taken off, so that what was typed about it is still in the file.
      assertThat(flipped.reason).contains(HOLDER_REASON)
    }
  }

  @Test fun `undoing leaves every status as it was`() {
    explorerUiTest {
      openHeapDump(setAlready = { holderIsLeaking() }) { it.activityObjectId }
      changeStatus()
      choose(LeakStatus.NOT_LEAKING)
      write("this screen is deliberately kept")
      set()
      waitUntilAtLeastOneExists(hasText(UNDO_STATUS), SAVE_TIMEOUT_MILLIS)

      onNode(hasText(UNDO_STATUS) and isButton()).performClick()

      onNodeWithText(SOLVE_CONFLICTS).assertDoesNotExist()
      val overrides = statusFile().read()
      assertThat(overrides.all.map { it.objectId }).containsExactly(heapDump.holderObjectId)
      assertThat(overrides[heapDump.holderObjectId]!!.status).isEqualTo(LeakStatus.LEAKING)
      assertThat(overrides[heapDump.activityObjectId]).isNull()
    }
  }

  /**
   * What the verdicts are for: they are about objects, and the thing to go and fix is the one reference going
   * from an object that belongs in memory to one that doesn't.
   *
   * Both halves in one window, because they are one answer: the reference is marked from the verdicts either
   * side of it, so a verdict set by hand is what puts the mark on the chain and what takes it off again.
   */
  @Test fun `the chain marks which reference the leak is, and a hand can take the mark off`() {
    explorerUiTest {
      // Set in a run before this one, and what leaves a single reference below it: with nothing on this
      // chain known to belong in memory, the fault is at either of its two steps and neither is marked.
      openHeapDump(setAlready = { holderIsExpected() }) { it.activityObjectId }

      onNodeWithText("$FAULTY_STEP $FAULTY_REFERENCE").assertIsDisplayed()

      changeStatus()
      choose(LeakStatus.NOT_LEAKING)
      write(TYPED_REASON)
      set()

      // Nothing on this chain is stuck any more, so there is no reference to point at — and the step is
      // still drawn, which is the mark being about the leak rather than about the reference.
      waitUntilAtLeastOneExists(hasText(TYPED_REASON, substring = true), SAVE_TIMEOUT_MILLIS)
      onNodeWithText(FAULTY_REFERENCE, substring = true).assertDoesNotExist()
      onNodeWithText(FAULTY_STEP).assertIsDisplayed()
    }
  }

  /**
   * The other half of setting a status: the leaks are read through them, so the list changes rather than
   * only the colour of one object. See [shark.explorer.HeapDominatorTreemap.findLeaks].
   */
  @Test fun `an object set to leaking by hand is what the leaks screen lists`() {
    explorerUiTest {
      // Nothing is wrong with the holder as far as the heap dump is concerned: without this the list is the
      // destroyed activity it holds.
      openHeapDump(setAlready = { holderIsLeaking() })

      screenButton(Place.LEAKS_LABEL).performClick()

      waitUntilAtLeastOneExists(namesObject(heapDump.holderObjectId), OPEN_TIMEOUT_MILLIS)
      // And the activity is not on it any more: it is only still in memory because of the holder, so the
      // holder is the one thing to fix and the activity is listed under it.
      assertThat(onAllNodes(namesObject(heapDump.activityObjectId)).fetchSemanticsNodes()).isEmpty()
    }
  }

  /**
   * Opens the window on the heap dump, on a tab showing [objectId] the way a link to that object does, or on
   * the heap dump itself when it is null.
   *
   * Which object is asked for as a function of the dump, since an address only exists once it is written.
   */
  private fun ComposeUiTest.openHeapDump(
    setAlready: () -> Unit = {},
    objectId: (LeakyChainHeapDump) -> Long? = { null }
  ) {
    heapDump = testFolder.leakyChainHeapDump()
    setAlready()
    val place = objectId(heapDump)?.let { Place.Object(it) }
    setContent {
      MaterialTheme {
        ExplorerApp(
          heapDumpFile = heapDump.file,
          linkedPlaces = listOfNotNull(place),
          // A directory of this test's, never `~/.shark-explorer`: a test that saved into the real one would
          // rewrite the conclusions of whoever is running it.
          leakStatuses = ExplorerLeakStatuses(statusesRoot),
          // Nothing here opens a second heap dump, and which window one would land in is
          // `ExplorerWindowTest`'s.
          onHeapDumpChosen = { _, _ -> },
          // An `adb` connected to nothing, rather than the one on this machine.
          deviceHeapDumps = DeviceHeapDumps(NO_DEVICE_ADB)
        )
      }
    }
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
    if (place != null) {
      // The panes describe the object a little after the tab opens, since describing it is a read of the heap
      // dump: the pencil that changes its status is the first thing that says they have.
      waitUntilAtLeastOneExists(hasText(EDIT_STATUS_GLYPH), OPEN_TIMEOUT_MILLIS)
    }
  }

  /**
   * Opens the dialog that sets a status.
   *
   * Waits for the button to be enabled rather than pressing it as it is: it stays disabled until the file
   * has been read, which is what keeps a save from deleting statuses still on their way off the disk.
   */
  private fun ComposeUiTest.changeStatus() {
    val pencil = hasText(EDIT_STATUS_GLYPH) and hasClickAction()
    waitUntilAtLeastOneExists(pencil and isEnabled(), RENDER_TIMEOUT_MILLIS)
    onNode(pencil).performClick()
    onNodeWithText(statusDialogTitle(ACTIVITY_NAME)).assertIsDisplayed()
  }

  /** Picks one of the three statuses, by the row it is on rather than by the mark beside it. */
  private fun ComposeUiTest.choose(status: LeakStatus) {
    onNode(hasText(status.statusText) and hasClickAction()).performClick()
  }

  private fun ComposeUiTest.write(reason: String) {
    onNodeWithContentDescription(REASON_DESCRIPTION).performTextInput(reason)
  }

  private fun ComposeUiTest.set() {
    setButton().performClick()
  }

  private fun ComposeUiTest.setButton() = onNode(hasText(SAVE_STATUS) and isButton())

  /** The status at the top of the panel, which is the glyph and the status and nothing else. */
  private fun shows(status: LeakStatus) = hasText("${status.glyphOf()} ${status.statusText}")

  /** Repeated from the section rather than shared: a glyph is one of the words the window says. */
  private fun LeakStatus.glyphOf() = when (this) {
    LeakStatus.NOT_LEAKING -> "✓"
    LeakStatus.UNKNOWN -> "?"
    LeakStatus.LEAKING -> "✗"
  }

  /** A status set on the holder in a run before the one under test, which is the file being there. */
  private fun holderIsLeaking() = holderWasSetTo(LeakStatus.LEAKING, HOLDER_REASON)

  /** And the other way: a holder that belongs in memory, with the activity below it still stuck. */
  private fun holderIsExpected() = holderWasSetTo(LeakStatus.NOT_LEAKING, HOLDER_EXPECTED_REASON)

  private fun holderWasSetTo(
    status: LeakStatus,
    reason: String
  ) {
    statusFile().write(
      LeakStatusOverrides.of(
        listOf(
          LeakStatusOverride(
            objectId = heapDump.holderObjectId,
            status = status,
            reason = reason
          )
        )
      )
    )
  }

  private fun statusFile() = LeakStatusFile(statusesRoot, heapDump.file)

  /** A row of the leaks screen, which names the object it is about by its address. */
  private fun namesObject(objectId: Long) = hasText(hexObjectId(objectId), substring = true)

  /** A button on the row of screens an open heap dump can be read through. See [ExplorerAppTest]. */
  private fun ComposeUiTest.screenButton(label: String) = onNode(hasText(label) and isButton())

  private fun isButton(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

  companion object {
    /** Opening a heap dump and laying its tree out both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** And so does describing an object of it. */
    private const val RENDER_TIMEOUT_MILLIS = 5_000L

    /** Setting a status is the heap dump read for what it disagrees with, and then a file written. */
    private const val SAVE_TIMEOUT_MILLIS = 10_000L

    /** How the window says a status is somebody's rather than the heap dump's. */
    private const val SET_BY_HAND = "set by hand — "

    /** What a test types as the reason, which is the sentence the file has to come back with. */
    private const val TYPED_REASON = "this screen is deliberately kept for one more frame"

    /** What the inspector says about the destroyed activity, which is what a hand overrules. */
    private const val DESTROYED_REASON = "Activity#mDestroyed is true"

    /** What the object the tab is on is called where the dialog names it. */
    private const val ACTIVITY_NAME = "MainActivity instance"

    /** And what the object holding it is called, where the dialog names that one. */
    private const val HOLDER_NAME = "Holder instance"

    /** The step of the chain that holds the destroyed activity, which is the reference to clear. */
    private const val FAULTY_STEP = "Holder.activity"

    /** And what it was given as its reason, which the dialog has to show to be overruled. */
    private const val HOLDER_REASON = "this holder is the one to fix"

    /** The reason for the other status a run before this one set on the holder. */
    private const val HOLDER_EXPECTED_REASON = "this holder is the app's own cache"

    /** What the dialog says about a status set on an object that holds the one being changed. */
    private const val CONFLICT_ABOVE = "holds it"

    private const val CONFLICT_BECOMES = "Would become:"

    /** An `adb` connected to nothing, so that a test doesn't answer for whatever is plugged in. */
    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }
  }
}

/**
 * A heap dump with a destroyed activity in it and the object holding it, which is the smallest chain two
 * statuses can disagree along: what a leaking object holds is leaking, so a holder that is leaking and an
 * activity that isn't cannot both be read off it.
 */
private fun TemporaryFolder.leakyChainHeapDump(): LeakyChainHeapDump {
  val file = newFile("leaky-chain.hprof")
  var activityObjectId = 0L
  var holderObjectId = 0L
  file.dump {
    val activityClassId = clazz(
      className = LEAKING_ACTIVITY_CLASS_NAME,
      // Field values are written most derived class first, and the subclass declares none, so an instance
      // of it is written with the one field it inherits.
      superclassId = clazz(
        className = "android.app.Activity",
        fields = listOf("mDestroyed" to BooleanHolder::class)
      )
    )
    val activity = instance(activityClassId, fields = listOf(BooleanHolder(true)))
    val holder = instance(
      clazz(className = "com.example.Holder", fields = listOf("activity" to ReferenceHolder::class)),
      fields = listOf(activity)
    )
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    activityObjectId = activity.value
    holderObjectId = holder.value
  }
  return LeakyChainHeapDump(file, activityObjectId, holderObjectId)
}

private class LeakyChainHeapDump(
  val file: File,
  /** The destroyed activity, which the inspectors recognize on their own. */
  val activityObjectId: Long,
  /** And what holds it, which nothing knows either way about. */
  val holderObjectId: Long
)
