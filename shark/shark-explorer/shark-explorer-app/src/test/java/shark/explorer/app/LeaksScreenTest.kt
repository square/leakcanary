package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.Adb
import shark.explorer.AdbOutput
import shark.explorer.DeviceHeapDumps
import shark.explorer.ExplorerScreen
import shark.explorer.HeapLeaks
import shark.explorer.HeapObjectKind
import shark.explorer.LeakGroup
import shark.explorer.LeakKind
import shark.explorer.LeakSection
import shark.explorer.LeakingObject
import shark.explorer.ReachabilityStrength
import shark.explorer.hexObjectId

/**
 * The screen listing what shouldn't be in memory, and the colouring that shades the map by it.
 *
 * What it is about is that a leak is one thing however many objects of it there are, and that every row of
 * it leads into the object explorer: there is no leak trace here, because the chain beside the map is one.
 * [ExplorerAppTest] covers the rest of the window.
 */
@OptIn(ExperimentalTestApi::class)
class LeaksScreenTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  /** Every UI test here records what Shark logged. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  /** The heap dump the window is opened on, and the objects of it these tests ask about. */
  private lateinit var heapDump: LeakyHeapDump

  @Test fun `the leaks of a heap dump are listed in three parts`() {
    explorerUiTest {
      openLeaks()

      // All three, however few of them were found: an empty part says that kind of leak was looked for.
      LeakKind.values().forEach { kind ->
        onNodeWithText("${kind.title} ·", substring = true).assertIsDisplayed()
      }
      onNodeWithText(PRESENTER_LEAK_NAME, substring = true).assertIsDisplayed()
      onNodeWithText(ACTIVITY_LEAK_NAME, substring = true).assertIsDisplayed()
    }
  }

  @Test fun `the first object of a leak is on the list and the rest are behind one row`() {
    explorerUiTest {
      openLeaks()

      // Two destroyed activities, held the same way, so one leak: one of them is under it and the other is
      // a row saying there is one more, until that row is pressed.
      onNodeWithText("2 objects").assertIsDisplayed()
      assertThat(heapDump.activityObjectIds.filter { nodesNaming(it).isNotEmpty() }).hasSize(1)

      openTheRest()

      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        heapDump.activityObjectIds.all { nodesNaming(it).isNotEmpty() }
      }
    }
  }

  @Test fun `a leak with one object in it has nothing to open`() {
    explorerUiTest {
      openLeaks()

      // Its object is already on the list, so a row to open would open nothing. The one on screen is the
      // other leak's, which has two.
      onNodeWithText("1 object").assertIsDisplayed()
      assertThat(nodesNaming(heapDump.watchedObjectId)).isNotEmpty()
      assertThat(onAllNodesWithText(LEAKING_THE_SAME_WAY, substring = true).fetchSemanticsNodes())
        .hasSize(1)
    }
  }

  @Test fun `why an object is leaking is said once per object, not once per leak`() {
    explorerUiTest {
      openLeaks()
      openTheRest()
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        heapDump.activityObjectIds.all { nodesNaming(it).isNotEmpty() }
      }

      // An inspector reads it off the object, so two objects of one leak can be leaking for reasons that
      // don't read the same — and the row above them says what the leak is, which is the references.
      val destroyed = onAllNodes(hasText(DESTROYED_REASON, substring = true) and hasClickAction())
      assertThat(destroyed.fetchSemanticsNodes()).hasSize(heapDump.activityObjectIds.size)
    }
  }

  @Test fun `clicking an object of a leak goes to it on the map`() {
    explorerUiTest {
      openLeaks()

      onNode(namesObject(heapDump.watchedObjectId) and hasClickAction()).performClick()

      // Which is the whole point of the list: it names objects, and an object is something the explorer
      // already knows how to show.
      waitUntilTheLeaksAreLeft()
      assertThat(nodesNaming(heapDump.watchedObjectId)).isNotEmpty()
    }
  }

  @Test fun `every leak is named by a hash of how it is held`() {
    explorerUiTest {
      openLeaks()

      // The addresses under a leak are of this heap dump and this is the same in the next one, which is
      // what makes a leak something to write in a bug report.
      val signatures = onAllNodesWithText(SIGNATURE, substring = true).fetchSemanticsNodes()
        .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
        .filter { it.startsWith(SIGNATURE) }
      assertThat(signatures).hasSize(LEAK_COUNT)
      assertThat(signatures).allSatisfy { assertThat(it).matches("\\Q$SIGNATURE\\E [0-9a-f]{40}") }
    }
  }

  @Test fun `what the app told LeakCanary about an object is on the row, and leads to the record`() {
    explorerUiTest {
      openLeaks()

      onNodeWithText(WATCHED, substring = true).performClick()

      // The KeyedWeakReference is an object of the heap dump like any other, so the line about it opens it.
      waitUntilTheLeaksAreLeft()
      assertThat(nodesNaming(heapDump.weakReferenceObjectId)).isNotEmpty()
    }
  }

  @Test fun `a leak is named after both ends of the references it is`() {
    explorerUiTest {
      // Rendered from leaks rather than read off a heap dump: what is being pinned is how the two ends of a
      // leak are named, and a dump whose leaks have the shape to show it is a dump built for it.
      setContent { MaterialTheme { LeaksScreen(TWO_ENDED_LEAKS, false, emptySet(), {}, {}) } }

      // Both ends and a gap for what is between them, then the leak whose two ends are one reference. Each
      // ends on an arrow, because what it points at is the object on the row below.
      onNodeWithText("$FIRST_END $STRETCH_ARROW $STRETCH_GAP $STRETCH_ARROW $LAST_END $STRETCH_ARROW")
        .assertIsDisplayed()
      onNodeWithText("$ONE_REFERENCE $STRETCH_ARROW").assertIsDisplayed()
    }
  }

  @Test fun `the map is shaded by what is leaking as soon as it is drawn`() {
    explorerUiTest {
      openHeapDump()

      // Nothing is ticked to make this happen: the leaks are looked for as the heap dump opens, and the
      // box says how many there are once they are found.
      leakToggle().assertIsOn()
      waitUntilAtLeastOneExists(hasText("$LEAKING $LEAKING_OBJECT_COUNT"), OPEN_TIMEOUT_MILLIS)

      leakToggle().performClick()

      leakToggle().assertIsOff()
    }
  }

  @Test fun `shading the leaks and colouring the map by strength are one choice`() {
    explorerUiTest {
      openHeapDump()
      leakToggle().assertIsOn()

      strengthToggle().performClick()

      // Grey underneath is what leaves the few objects that shouldn't be there as the only colour on the
      // map, so ticking a strength unticks the leaks — and ticking the leaks back on unticks the strengths.
      leakToggle().assertIsOff()
      leakToggle().performClick()
      strengthToggle().assertIsOff()
    }
  }

  @Test fun `a chain says which of its objects are meant to be gone, unasked`() {
    explorerUiTest {
      openHeapDump()
      // Reached through the list of every object rather than through the leaks, because that is the point:
      // whatever a chain was built for, the inspectors have run over the objects on it.
      screenButton(ExplorerScreen.OBJECTS_LABEL).performClick()
      // Filtered down to it rather than scrolled to it: the activities of this dump retain the least of
      // anything in it, so they are the last rows of a list that is longer than the window.
      onNode(hasSetTextAction()).performTextInput(LEAKING_ACTIVITY_CLASS_NAME.substringAfterLast('.'))
      val listed = "$LEAKING_ACTIVITY_CLASS_NAME instance"
      waitUntilAtLeastOneExists(hasText(listed), OPEN_TIMEOUT_MILLIS)

      onAllNodesWithText(listed)[0].performClick()

      waitUntilAtLeastOneExists(hasText("$LEAKING: ", substring = true), OPEN_TIMEOUT_MILLIS)
      assertThat(onAllNodesWithText("mDestroyed", substring = true).fetchSemanticsNodes()).isNotEmpty()
    }
  }

  @Test fun `the leaks are looked for once however often they are read`() {
    explorerUiTest {
      openLeaks()

      screenButton(ExplorerScreen.OBJECTS_LABEL).performClick()
      screenButton(ExplorerScreen.LEAKS_LABEL).performClick()
      waitUntilAtLeastOneExists(hasText("${LeakKind.APPLICATION.title} ·", substring = true), OPEN_TIMEOUT_MILLIS)
    }

    // A pass over every instance of the heap dump and a walk up to the GC roots per object found, so
    // leaving the screen and coming back has to be the answer already worked out.
    assertThat(logged.filter { it.startsWith(READING_THE_LEAKS) }).hasSize(1)
  }

  /** Opens the window on [leakyHeapDump] and waits for the tree it draws. */
  private fun ComposeUiTest.openHeapDump() {
    heapDump = testFolder.leakyHeapDump()
    setContent {
      MaterialTheme {
        ExplorerApp(
          heapDumpFile = heapDump.file,
          // Nothing here opens a second heap dump, and which window one would land in is
          // `ExplorerWindowTest`'s.
          onHeapDumpChosen = { _, _ -> },
          deviceHeapDumps = DeviceHeapDumps(NO_DEVICE_ADB)
        )
      }
    }
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
  }

  /** And goes on to the leaks, waiting for the pass over the heap dump that finds them. */
  private fun ComposeUiTest.openLeaks() {
    openHeapDump()
    screenButton(ExplorerScreen.LEAKS_LABEL).performClick()
    waitUntilAtLeastOneExists(
      hasText("${LeakKind.APPLICATION.title} ·", substring = true),
      OPEN_TIMEOUT_MILLIS
    )
  }

  /** Waits until the list of leaks is gone, which is what following a row out of it does. */
  private fun ComposeUiTest.waitUntilTheLeaksAreLeft() {
    waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
      onAllNodesWithText("${LeakKind.APPLICATION.title} ·", substring = true)
        .fetchSemanticsNodes()
        .isEmpty()
    }
  }

  /** Presses the row standing for the objects of a leak past the first, which shows them. */
  private fun ComposeUiTest.openTheRest() {
    onNodeWithText(LEAKING_THE_SAME_WAY, substring = true).performClick()
  }

  /** Everything on screen naming [objectId], which is how a list of objects says which ones they are. */
  private fun ComposeUiTest.nodesNaming(objectId: Long) =
    onAllNodesWithText(hexObjectId(objectId), substring = true).fetchSemanticsNodes()

  private fun namesObject(objectId: Long) = hasText(hexObjectId(objectId), substring = true)

  /** A button on the row of screens an open heap dump can be read through. */
  private fun ComposeUiTest.screenButton(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and hasClickAction())

  /** The checkbox above the view that shades the objects that shouldn't be in memory. */
  private fun ComposeUiTest.leakToggle(): SemanticsNodeInteraction =
    onNode(hasText(LEAKING, substring = true) and isToggleable())

  /** And one of the boxes beside it that colour the map by how firmly an object is held. */
  private fun ComposeUiTest.strengthToggle(): SemanticsNodeInteraction =
    onNode(hasText(ReachabilityStrength.STRONG.displayName, substring = true) and isToggleable())

  companion object {
    /** The two destroyed activities and the watched object of [leakyHeapDump]. */
    private const val LEAKING_OBJECT_COUNT = 3

    /** Which are two leaks: the activities leak the same way, so they are one row with both in it. */
    private const val LEAK_COUNT = 2

    /** What the row about a watched object starts with, past the glyph in front of it. */
    private const val WATCHED = "Watched · key"

    /** Part of what the inspector says about a destroyed activity, which is why it shouldn't be here. */
    private const val DESTROYED_REASON = "mDestroyed"

    /** How the log says the pass over the heap dump that finds the leaks was started. */
    private const val READING_THE_LEAKS = "Reading the leaking objects of"

    /** Opening a heap dump, laying the tree out and finding the leaks all happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** An `adb` connected to nothing, so that a test doesn't answer for whatever is plugged in. */
    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }

    /** The reference that shouldn't be holding, which is what a leak of more than one is named after. */
    private const val FIRST_END = "Holder.middle"

    /** And the one that points straight at what leaked, three references down from it. */
    private const val LAST_END = "Third.activity"

    /** The leak whose two ends are the same reference, which is most of them. */
    private const val ONE_REFERENCE = "Holder.activity"

    /** Two app leaks, one of each shape. See [LeakGroup.suspectPath]. */
    private val TWO_ENDED_LEAKS = HeapLeaks(
      listOf(
        LeakSection(
          kind = LeakKind.APPLICATION,
          groups = listOf(
            leakGroup(listOf(FIRST_END, "Middle.third", LAST_END)),
            leakGroup(listOf(ONE_REFERENCE))
          )
        )
      )
    )

    private fun leakGroup(suspectPath: List<String>) = LeakGroup(
      signature = suspectPath.first().sha1OfNothing(),
      title = suspectPath.first(),
      suspectPath = suspectPath,
      subtitle = null,
      objects = listOf(
        LeakingObject(
          objectId = suspectPath.first().hashCode().toLong(),
          className = "com.example.MainActivity",
          kind = HeapObjectKind.INSTANCE,
          headline = null,
          retainedSize = 0L,
          retainedCount = 1,
          strength = ReachabilityStrength.STRONG,
          leakingReason = null,
          watcher = null
        )
      )
    )

    /** Any forty characters of hex: what the row does with a signature is show it. */
    private fun String.sha1OfNothing() = "%040x".format(hashCode().toLong() and 0xffffffffL)
  }
}
