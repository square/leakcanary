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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.Adb
import shark.explorer.AdbOutput
import shark.explorer.DeviceHeapDumps
import shark.explorer.ExplorerScreen
import shark.explorer.LeakKind
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
      onNodeWithText(PRESENTER_LEAK_NAME).assertIsDisplayed()
      onNodeWithText(ACTIVITY_LEAK_NAME).assertIsDisplayed()
    }
  }

  @Test fun `objects leaking the same way are one row until it is unfolded`() {
    explorerUiTest {
      openLeaks()

      // Two destroyed activities, held the same way, so one leak: the row says how many and shows none of
      // them until it is pressed.
      onNodeWithText("2 objects").assertIsDisplayed()
      assertThat(nodesNaming(heapDump.activityObjectIds[0])).isEmpty()

      unfold(ACTIVITY_LEAK_NAME, heapDump.activityObjectIds[0])

      assertThat(nodesNaming(heapDump.activityObjectIds[1])).isNotEmpty()
    }
  }

  @Test fun `a leak with one object in it folds like every other leak`() {
    explorerUiTest {
      openLeaks()

      // A row that led somewhere when it held one object and unfolded when it held two would be two rows
      // that look the same and do different things.
      onNodeWithText("1 object").assertIsDisplayed()
      assertThat(nodesNaming(heapDump.watchedObjectId)).isEmpty()

      unfold(PRESENTER_LEAK_NAME, heapDump.watchedObjectId)
    }
  }

  @Test fun `clicking an object of a leak goes to it on the map`() {
    explorerUiTest {
      openLeaks()
      unfold(PRESENTER_LEAK_NAME, heapDump.watchedObjectId)

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
      unfold(PRESENTER_LEAK_NAME, heapDump.watchedObjectId)

      onNodeWithText(WATCHED, substring = true).performClick()

      // The KeyedWeakReference is an object of the heap dump like any other, so the line about it opens it.
      waitUntilTheLeaksAreLeft()
      assertThat(nodesNaming(heapDump.weakReferenceObjectId)).isNotEmpty()
    }
  }

  @Test fun `the map can be shaded by what is leaking`() {
    explorerUiTest {
      openHeapDump()
      leakToggle().assertIsOff()

      leakToggle().performClick()

      // Ticking it is what sends the explorer looking, so the row says how many it found once it has.
      waitUntilAtLeastOneExists(hasText("$LEAKING $LEAKING_OBJECT_COUNT"), OPEN_TIMEOUT_MILLIS)
      leakToggle().assertIsOn()
    }
  }

  @Test fun `shading the leaks and colouring the map by strength are one choice`() {
    explorerUiTest {
      openHeapDump()
      strengthToggle().assertIsOn()

      leakToggle().performClick()

      // Grey underneath is what leaves the few objects that shouldn't be there as the only colour on the
      // map, so ticking this unticks the strengths — and ticking a strength back on unticks this.
      strengthToggle().assertIsOff()
      strengthToggle().performClick()
      leakToggle().assertIsOff()
    }
  }

  @Test fun `a chain says which of its objects are meant to be gone, unasked`() {
    explorerUiTest {
      openHeapDump()
      // Reached through the list of every object rather than through the leaks, because that is the point:
      // whatever a chain was built for, the inspectors have run over the objects on it.
      screenButton(ExplorerScreen.OBJECTS_LABEL).performClick()
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

  /** Presses a leak's row, which unfolds it, and waits for the objects it holds. */
  private fun ComposeUiTest.unfold(
    leakName: String,
    firstObjectId: Long
  ) {
    onNodeWithText(leakName).performClick()
    waitUntilAtLeastOneExists(namesObject(firstObjectId), OPEN_TIMEOUT_MILLIS)
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

    /** How the log says the pass over the heap dump that finds the leaks was started. */
    private const val READING_THE_LEAKS = "Reading the leaking objects of"

    /** Opening a heap dump, laying the tree out and finding the leaks all happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** An `adb` connected to nothing, so that a test doesn't answer for whatever is plugged in. */
    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }
  }
}
