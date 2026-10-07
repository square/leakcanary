package shark.dive.app

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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
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
import shark.dive.Adb
import shark.dive.AdbOutput
import shark.dive.DeviceHeapDumps
import shark.dive.Verdict
import shark.dive.VerdictFile
import shark.dive.VerdictOverride
import shark.dive.VerdictOverrides
import shark.dive.Place
import shark.dive.ReferencePage
import shark.dive.Topic
import shark.dive.hexObjectId
import shark.dive.text

/**
 * Whether the object a tab is on is meant to be in memory, said at the top of the panel that says what the
 * object is, changed by hand from there, and what the answer marks on the path beside it.
 *
 * What the verdicts mean and how two of them disagree is `VerdictTest` and `HeapVerdictTest` in
 * `shark-dive-core`, and where they are kept is `VerdictFileTest`. What is only true here is that the
 * panel says what the heap dump says, that changing one asks for the reason before it writes anything, that
 * a verdict which cannot be true alongside another is shown rather than settled quietly, and that the path
 * says which reference the leak is.
 */
@OptIn(ExperimentalTestApi::class)
class VerdictSectionTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  /** Every UI test here records what Shark logged. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  /** Where the verdicts of the heap dump under test are kept, which is this test's own directory. */
  private val verdictsRoot by lazy { testFolder.newFolder("verdicts") }

  private lateinit var heapDump: LeakyPathHeapDump

  @Test fun `an object the inspectors say should be gone says so in the panel`() {
    diveUiTest {
      openHeapDump { it.activityObjectId }

      onNodeWithText(VERDICT_LABEL).assertIsDisplayed()
      onNode(shows(Verdict.STUCK)).assertIsDisplayed()
      // And why, because a verdict is a conclusion and half of them are about another object. The reason as
      // the panel has it, which is what the path beside it prefixes with the verdict.
      onNodeWithText(DESTROYED_REASON).assertIsDisplayed()
    }
  }

  /** Quietly, because most of a heap dump is this and a line that shouted it would be read by nobody. */
  @Test fun `an object nothing knows either way about says that`() {
    diveUiTest {
      openHeapDump { it.holderObjectId }

      onNode(shows(Verdict.UNKNOWN)).assertIsDisplayed()
    }
  }

  /** There is nothing to inspect about the heap dump as a whole, and nothing to decide about it either. */
  @Test fun `the tab on the whole heap dump has no verdict`() {
    diveUiTest {
      openHeapDump()

      onNodeWithText(EDIT_VERDICT_GLYPH).assertDoesNotExist()
      onNode(shows(Verdict.UNKNOWN)).assertDoesNotExist()
    }
  }

  @Test fun `a verdict set by hand is what the panel says, and it is on disk`() {
    diveUiTest {
      openHeapDump { it.activityObjectId }
      changeVerdict()

      choose(Verdict.EXPECTED)
      write(TYPED_REASON)
      set()

      waitUntilAtLeastOneExists(shows(Verdict.EXPECTED), SAVE_TIMEOUT_MILLIS)
      // Marked as somebody's rather than the heap dump's, which is the difference between reading the dump
      // and reading a conclusion about it, and with what it overruled after it.
      onNodeWithText("$SET_BY_HAND$TYPED_REASON. Conflicts with $DESTROYED_REASON").assertIsDisplayed()
      waitUntil(timeoutMillis = SAVE_TIMEOUT_MILLIS) {
        verdictFile().read()[heapDump.activityObjectId]?.verdict == Verdict.EXPECTED
      }
      assertThat(verdictFile().read()[heapDump.activityObjectId]!!.reason).isEqualTo(TYPED_REASON)
    }
  }

  /** The whole of why a verdict set by hand is worth keeping: without the why it is a colour somebody chose. */
  @Test fun `a verdict cannot be set without a reason`() {
    diveUiTest {
      openHeapDump { it.activityObjectId }
      changeVerdict()

      choose(Verdict.EXPECTED)

      setButton().assertIsNotEnabled()
      write("because I read the code")
      setButton().assertIsEnabled()
    }
  }

  @Test fun `a verdict set by hand can be taken back off`() {
    diveUiTest {
      openHeapDump { it.activityObjectId }
      changeVerdict()
      choose(Verdict.EXPECTED)
      write("this screen is deliberately kept")
      set()
      waitUntilAtLeastOneExists(shows(Verdict.EXPECTED), SAVE_TIMEOUT_MILLIS)

      // Which is the one thing only the dialog of a verdict already set offers.
      changeVerdict()
      onNode(hasText(CLEAR_VERDICT) and isButton()).performClick()

      // And the heap dump says what it said about the object again.
      waitUntilAtLeastOneExists(shows(Verdict.STUCK), SAVE_TIMEOUT_MILLIS)
      waitUntil(timeoutMillis = SAVE_TIMEOUT_MILLIS) { verdictFile().read().isEmpty }
    }
  }

  @Test fun `a verdict that cannot be true alongside another is shown before anything is written`() {
    diveUiTest {
      // Set in a run before this one: the holder above the activity is leaking, so everything it holds is.
      openHeapDump(setAlready = { holderIsLeaking() }) { it.activityObjectId }
      changeVerdict()

      choose(Verdict.EXPECTED)
      write("this screen is deliberately kept")
      set()

      // The one it disagrees with, by name, with what it was given as its reason: whoever is about to
      // overrule it is the only person who can weigh the two, and only if they can read it.
      waitUntilAtLeastOneExists(hasText("$HOLDER_NAME $CONFLICT_ABOVE"), SAVE_TIMEOUT_MILLIS)
      onNodeWithText("${Verdict.STUCK.text}: $HOLDER_REASON").assertIsDisplayed()
      onNodeWithText("$CONFLICT_BECOMES ${Verdict.EXPECTED.text}")
        .assertIsDisplayed()
      // And nothing written while the question is open, which is what makes undoing it free.
      assertThat(verdictFile().read().all.map { it.verdict }).containsExactly(Verdict.STUCK)
    }
  }

  @Test fun `keeping the new verdict flips every verdict that disagreed with it`() {
    diveUiTest {
      openHeapDump(setAlready = { holderIsLeaking() }) { it.activityObjectId }
      changeVerdict()
      choose(Verdict.EXPECTED)
      write("this screen is deliberately kept")
      set()
      waitUntilAtLeastOneExists(hasText(SOLVE_CONFLICTS), SAVE_TIMEOUT_MILLIS)

      onNode(hasText(SOLVE_CONFLICTS) and isButton()).performClick()

      waitUntil(timeoutMillis = SAVE_TIMEOUT_MILLIS) {
        verdictFile().read()[heapDump.activityObjectId] != null
      }
      val overrides = verdictFile().read()
      assertThat(overrides[heapDump.activityObjectId]!!.verdict).isEqualTo(Verdict.EXPECTED)
      val flipped = overrides[heapDump.holderObjectId]!!
      assertThat(flipped.verdict).isEqualTo(Verdict.EXPECTED)
      // Flipped rather than taken off, so that what was typed about it is still in the file.
      assertThat(flipped.reason).contains(HOLDER_REASON)
    }
  }

  @Test fun `undoing leaves every verdict as it was`() {
    diveUiTest {
      openHeapDump(setAlready = { holderIsLeaking() }) { it.activityObjectId }
      changeVerdict()
      choose(Verdict.EXPECTED)
      write("this screen is deliberately kept")
      set()
      waitUntilAtLeastOneExists(hasText(UNDO_VERDICT), SAVE_TIMEOUT_MILLIS)

      onNode(hasText(UNDO_VERDICT) and isButton()).performClick()

      onNodeWithText(SOLVE_CONFLICTS).assertDoesNotExist()
      val overrides = verdictFile().read()
      assertThat(overrides.all.map { it.objectId }).containsExactly(heapDump.holderObjectId)
      assertThat(overrides[heapDump.holderObjectId]!!.verdict).isEqualTo(Verdict.STUCK)
      assertThat(overrides[heapDump.activityObjectId]).isNull()
    }
  }

  /**
   * What the verdicts are for: they are about objects, and the thing to go and fix is the one reference going
   * from an object that belongs in memory to one that doesn't.
   *
   * Both halves in one window, because they are one answer: the reference is marked from the verdicts either
   * side of it, so a verdict set by hand is what puts the mark on the path and what takes it off again.
   */
  @Test fun `the path names which reference the leak is, twice, and a hand can take that off`() {
    diveUiTest {
      // Set in a run before this one, and what leaves a single reference below it: with nothing on this
      // path known to belong in memory, the fault is at either of its two steps and neither is marked.
      openHeapDump(setAlready = { holderIsExpected() }) { it.activityObjectId }

      onNodeWithText("$FAULTY_STEP $FAULTY_REFERENCE").assertIsDisplayed()
      // And said again above the path, which is not a duplicate: a real path is tens of steps, this pane
      // is scrolled to the last of them, and a mark somewhere in the middle is an answer to go looking for.
      // The exact text is the section's, the mark on the path having the words above after it.
      onNodeWithText(LEAK_SOLVED).assertIsDisplayed()
      onNodeWithText(FAULTY_STEP).assertIsDisplayed()

      changeVerdict()
      choose(Verdict.EXPECTED)
      write(TYPED_REASON)
      set()

      // Nothing on this path is stuck any more, so there is no reference to point at and nothing is solved
      // — and the step is still drawn, which is both of those being about the leak rather than the reference.
      waitUntilAtLeastOneExists(hasText(TYPED_REASON, substring = true), SAVE_TIMEOUT_MILLIS)
      onNodeWithText(FAULTY_REFERENCE, substring = true).assertDoesNotExist()
      onNodeWithText(LEAK_SOLVED).assertDoesNotExist()
      // Which now matches the step on the path rather than the section that was above it.
      onNodeWithText(FAULTY_STEP).assertIsDisplayed()
    }
  }

  /**
   * The GC root is a reference too, the one holding the first object of the path, so a stuck object a root
   * holds directly is a leak of that root. See [shark.dive.isGcRootFaulty].
   */
  @Test fun `a stuck object a GC root holds names the root as the faulty reference`() {
    diveUiTest {
      openHeapDump(setAlready = { holderIsLeaking() }) { it.activityObjectId }

      // On the root's own line of the path, marked the way a field is, and said again above the path.
      onNodeWithText("$JNI_GLOBAL_ROOT $FAULTY_REFERENCE").assertIsDisplayed()
      onNodeWithText(LEAK_SOLVED).assertIsDisplayed()
      onNodeWithText(JNI_GLOBAL_ROOT).assertIsDisplayed()
      // And on no field, since the root holds the holder through none.
      onNodeWithText("$FAULTY_STEP $FAULTY_REFERENCE").assertDoesNotExist()
    }
  }

  /**
   * What the dialog belonging to its tab rather than to the window is for.
   *
   * The reason a verdict was given is the case for the other reading, and weighing it against yours is
   * sometimes going and looking at the object — which a dialog over the window can only offer by being
   * dismissed, and dismissing it throws away the reason that had been typed. Here it is a tab, and the tab
   * it was started in is still half set when you come back to it. See [VerdictSetter].
   */
  @Test fun `going to look at a verdict this one disagrees with leaves it half set`() {
    diveUiTest {
      openHeapDump(setAlready = { holderIsLeaking() }) { it.activityObjectId }
      changeVerdict()
      choose(Verdict.EXPECTED)
      write(TYPED_REASON)
      set()
      waitUntilAtLeastOneExists(hasText("$HOLDER_NAME $CONFLICT_ABOVE"), SAVE_TIMEOUT_MILLIS)

      onNodeWithText("$HOLDER_NAME $CONFLICT_ABOVE").performClick()

      // In front, the way a `?` opens a page: clicking it is asking to read the object, and the tab it was
      // opened from is where the question waits.
      // By address, which is what a tab strip of several instances of one class is told apart by.
      waitUntilAtLeastOneExists(tabOn(heapDump.holderObjectId), OPEN_TIMEOUT_MILLIS)
      onNode(tabOn(heapDump.activityObjectId)).performClick()
      onNodeWithText(settingVerdictTitle(ACTIVITY_NAME)).assertIsDisplayed()
      onNodeWithText(SOLVE_CONFLICTS).assertIsDisplayed()
      // Nothing written by any of that, which is what makes going to look free.
      assertThat(verdictFile().read().all.map { it.objectId }).containsExactly(heapDump.holderObjectId)
    }
  }

  /**
   * And why two verdicts can disagree at all, which is a paragraph read once above a list read every time.
   *
   * The `?` everywhere else in the window opens the reference in a tab, and that is exactly what a dialog
   * over the window cannot do: the tab would open behind it, unreachable until it was dismissed.
   */
  @Test fun `why two verdicts disagree is behind the question mark, in a tab`() {
    diveUiTest {
      openHeapDump(setAlready = { holderIsLeaking() }) { it.activityObjectId }
      changeVerdict()
      choose(Verdict.EXPECTED)
      write(TYPED_REASON)
      set()
      waitUntilAtLeastOneExists(hasText(SOLVE_CONFLICTS), SAVE_TIMEOUT_MILLIS)
      val page = ReferencePage.of(Topic.CONFLICTING_VERDICTS)

      onNodeWithContentDescription("$MORE_ABOUT ${page.title}").performClick()

      waitUntilAtLeastOneExists(hasText(page.hint), OPEN_TIMEOUT_MILLIS)
      onNode(hasText(page.title) and isTab()).assertIsDisplayed()
    }
  }

  /**
   * A reason is read the way a note is, because the object a verdict is about is usually explained by another
   * one: the address it is named by is drawn as what is at it, and leads there. See [NoteReader].
   */
  @Test fun `an address in a reason is drawn as what is at it, and leads there`() {
    diveUiTest {
      openHeapDump { it.activityObjectId }
      changeVerdict()

      choose(Verdict.EXPECTED)
      write("kept on purpose by ${hexObjectId(heapDump.holderObjectId)}")
      set()

      // In the panel and on the step of the path the activity is, which are the same reason drawn twice.
      val named = "kept on purpose by $HOLDER_NAME (${hexObjectId(heapDump.holderObjectId)})"
      waitUntilAtLeastOneExists(hasText(named, substring = true), SAVE_TIMEOUT_MILLIS)
      onAllNodes(hasText(named, substring = true)).onFirst().performFirstLinkClick()

      // A tab of its own, the way a link in a note opens one.
      waitUntilAtLeastOneExists(tabOn(heapDump.holderObjectId), RENDER_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a link in a reason opens in a browser`() {
    val opened = mutableListOf<String>()
    diveUiTest {
      openHeapDump(openUrl = { opened += it }) { it.activityObjectId }
      changeVerdict()

      choose(Verdict.EXPECTED)
      write("kept on purpose, see $ISSUE_URL")
      set()

      // Shortened the way a note shortens it, since it is read the way a note is.
      waitUntilAtLeastOneExists(hasText(SHORT_ISSUE, substring = true), SAVE_TIMEOUT_MILLIS)
      onAllNodes(hasText(SHORT_ISSUE, substring = true)).onFirst().performFirstLinkClick()

      assertThat(opened).containsExactly(ISSUE_URL)
    }
  }

  /**
   * The other half of setting a verdict: the leaks are read through them, so the list changes rather than
   * only the colour of one object. See [shark.dive.HeapDominatorTreemap.findLeaks].
   */
  @Test fun `an object set to leaking by hand is what the leaks screen lists`() {
    diveUiTest {
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
    openUrl: (String) -> Unit = {},
    objectId: (LeakyPathHeapDump) -> Long? = { null }
  ) {
    heapDump = testFolder.leakyPathHeapDump()
    setAlready()
    val place = objectId(heapDump)?.let { Place.Object(it) }
    setContent {
      MaterialTheme {
        DiveApp(
          heapDumpFile = heapDump.file,
          linkedPlaces = listOfNotNull(place),
          // A directory of this test's, never `~/.shark-dive`: a test that saved into the real one would
          // rewrite the conclusions of whoever is running it.
          verdicts = DiveVerdicts(verdictsRoot),
          // Never this machine's browser, which a link in a reason would otherwise open.
          openUrl = openUrl,
          // Nothing here opens a second heap dump, and which window one would land in is
          // `DiveWindowTest`'s.
          onHeapDumpChosen = { _, _ -> },
          // An `adb` connected to nothing, rather than the one on this machine.
          deviceHeapDumps = DeviceHeapDumps(NO_DEVICE_ADB)
        )
      }
    }
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
    if (place != null) {
      // The panes describe the object a little after the tab opens, since describing it is a read of the heap
      // dump: the pencil that changes its verdict is the first thing that says they have.
      waitUntilAtLeastOneExists(hasText(EDIT_VERDICT_GLYPH), OPEN_TIMEOUT_MILLIS)
    }
  }

  /**
   * Opens the dialog that sets a verdict, which belongs to the tab the pencil was pressed in.
   *
   * Waits for the button to be enabled rather than pressing it as it is: it stays disabled until the file
   * has been read, which is what keeps a save from deleting verdicts still on their way off the disk.
   */
  private fun ComposeUiTest.changeVerdict() {
    val pencil = hasText(EDIT_VERDICT_GLYPH) and hasClickAction()
    waitUntilAtLeastOneExists(pencil and isEnabled(), RENDER_TIMEOUT_MILLIS)
    onNode(pencil).performClick()
    onNodeWithText(settingVerdictTitle(ACTIVITY_NAME)).assertIsDisplayed()
  }

  /** Picks one of the three verdicts, by the row it is on rather than by the mark beside it. */
  private fun ComposeUiTest.choose(verdict: Verdict) {
    onNode(hasText(verdict.text) and hasClickAction()).performClick()
  }

  private fun ComposeUiTest.write(reason: String) {
    onNodeWithContentDescription(REASON_DESCRIPTION).performTextInput(reason)
  }

  private fun ComposeUiTest.set() {
    setButton().performClick()
  }

  private fun ComposeUiTest.setButton() = onNode(hasText(SAVE_VERDICT) and isButton())

  /** The verdict at the top of the panel, which is the glyph and the verdict and nothing else. */
  private fun shows(verdict: Verdict) = hasText("${verdict.glyphOf()} ${verdict.text}")

  /** Repeated from the section rather than shared: a glyph is one of the words the window says. */
  private fun Verdict.glyphOf() = when (this) {
    Verdict.EXPECTED -> "✓"
    Verdict.UNKNOWN -> "?"
    Verdict.STUCK -> "✗"
  }

  /** A verdict set on the holder in a run before the one under test, which is the file being there. */
  private fun holderIsLeaking() = holderWasSetTo(Verdict.STUCK, HOLDER_REASON)

  /** And the other way: a holder that belongs in memory, with the activity below it still stuck. */
  private fun holderIsExpected() = holderWasSetTo(Verdict.EXPECTED, HOLDER_EXPECTED_REASON)

  private fun holderWasSetTo(
    verdict: Verdict,
    reason: String
  ) {
    verdictFile().write(
      VerdictOverrides.of(
        listOf(
          VerdictOverride(
            objectId = heapDump.holderObjectId,
            verdict = verdict,
            reason = reason
          )
        )
      )
    )
  }

  private fun verdictFile() = VerdictFile(verdictsRoot, heapDump.file)

  /** A row of the leaks screen, which names the object it is about by its address. */
  private fun namesObject(objectId: Long) = hasText(hexObjectId(objectId), substring = true)

  /** A button on the row of screens an open heap dump can be read through. See [DiveAppTest]. */
  private fun ComposeUiTest.screenButton(label: String) = onNode(hasText(label) and isButton())

  private fun isButton(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

  private fun isTab(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

  /** A tab open on one object, which the strip names by class and address. See `HeapDominatorTreemap`. */
  private fun tabOn(objectId: Long) = hasText(hexObjectId(objectId), substring = true) and isTab()

  companion object {
    /** Opening a heap dump and laying its tree out both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** And so does describing an object of it. */
    private const val RENDER_TIMEOUT_MILLIS = 5_000L

    /** Setting a verdict is the heap dump read for what it disagrees with, and then a file written. */
    private const val SAVE_TIMEOUT_MILLIS = 10_000L

    /** How the window says a verdict is somebody's rather than the heap dump's. */
    private const val SET_BY_HAND = "set by hand — "

    /** What a test types as the reason, which is the sentence the file has to come back with. */
    private const val TYPED_REASON = "this screen is deliberately kept for one more frame"

    /** What the inspector says about the destroyed activity, which is what a hand overrules. */
    private const val DESTROYED_REASON = "Activity#mDestroyed is true"

    /** What the object the tab is on is called where the dialog names it. */
    private const val ACTIVITY_NAME = "MainActivity instance"

    /** And what the object holding it is called, where the dialog names that one. */
    private const val HOLDER_NAME = "Holder instance"

    /** The step of the path that holds the destroyed activity, which is the reference to clear. */
    private const val FAULTY_STEP = "Holder.activity"

    /** What holds the holder, as the path's first line names it. */
    private const val JNI_GLOBAL_ROOT = "GC root: JNI global reference"

    /** And what it was given as its reason, which the dialog has to show to be overruled. */
    private const val HOLDER_REASON = "this holder is the one to fix"

    /** What a reason links to when it links out of the heap dump, and how it is drawn. */
    private const val ISSUE_URL = "https://github.com/square/leakcanary/issues/2841"
    private const val SHORT_ISSUE = "square/leakcanary#2841"

    /** The reason for the other verdict a run before this one set on the holder. */
    private const val HOLDER_EXPECTED_REASON = "this holder is the app's own cache"

    /** What the dialog says about a verdict set on an object that holds the one being changed. */
    private const val CONFLICT_ABOVE = "holds it"

    private const val CONFLICT_BECOMES = "Would become:"

    /** An `adb` connected to nothing, so that a test doesn't answer for whatever is plugged in. */
    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }
  }
}

/**
 * A heap dump with a destroyed activity in it and the object holding it, which is the smallest path two
 * verdicts can disagree along: what a leaking object holds is leaking, so a holder that is leaking and an
 * activity that isn't cannot both be read off it.
 */
private fun TemporaryFolder.leakyPathHeapDump(): LeakyPathHeapDump {
  val file = newFile("leaky-path.hprof")
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
  return LeakyPathHeapDump(file, activityObjectId, holderObjectId)
}

private class LeakyPathHeapDump(
  val file: File,
  /** The destroyed activity, which the inspectors recognize on their own. */
  val activityObjectId: Long,
  /** And what holds it, which nothing knows either way about. */
  val holderObjectId: Long
)
