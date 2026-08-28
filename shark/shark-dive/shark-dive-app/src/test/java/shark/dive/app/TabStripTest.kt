package shark.dive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.dive.Adb
import shark.dive.AdbOutput
import shark.dive.DeepLink
import shark.dive.DeviceHeapDumps
import shark.dive.HeapDominatorTreemap
import shark.dive.Place
import shark.dive.hexObjectId

/**
 * The tabs a window reads a heap dump through, and the clicks that open, move and close them.
 *
 * What this is about is the one rule the window's navigation is: clicking an object *inside* a tab moves
 * that tab, and everything else — the buttons on the bar, a middle click, a ⌘ click — opens a tab of its
 * own. Which is why every one of these tests counts tabs rather than looking at the panes: what the panes
 * say about an object is [DiveAppTest]'s.
 */
@OptIn(ExperimentalTestApi::class)
class TabStripTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  /** Every UI test here records what Shark logged. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  /** The object id of the array in [testHeapDump], recorded as the dump is written. */
  private var payloadObjectId = 0L

  @Test fun `a window opens on one tab, showing the whole heap dump`() {
    diveUiTest {
      openHeapDump()

      assertThat(tabs().fetchSemanticsNodes()).hasSize(1)
      tab(HeapDominatorTreemap.ROOT_LABEL).assertIsDisplayed()
    }
  }

  @Test fun `each press of a button on the bar opens another tab`() {
    diveUiTest {
      openHeapDump()

      // Always another one, never the tab that is already there: the bar is the way in to a heap dump, so
      // two lists of objects filtered differently are two useful tabs to have open at once.
      screenButton(Place.OBJECTS_LABEL).performClick()
      screenButton(Place.OBJECTS_LABEL).performClick()

      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { tabs().fetchSemanticsNodes().size == 3 }
    }
  }

  @Test fun `tabs that no longer fit across the window go on a line of their own`() {
    diveUiTest {
      openHeapDump()

      repeat(TABS_PAST_ONE_LINE) { screenButton(Place.OBJECTS_LABEL).performClick() }

      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        tabs().fetchSemanticsNodes().size == TABS_PAST_ONE_LINE + 1
      }
      // Every tab still readable and still on screen, rather than squeezed to nothing or scrolled off the
      // edge: which class and which instance of it is the whole of what a tab is for, and a tab you have
      // to go looking for is one you may as well not have opened.
      waitForIdle()
      val bounds = tabs().fetchSemanticsNodes().map { it.boundsInRoot }
      assertThat(bounds.map { it.top }.distinct()).hasSizeGreaterThan(1)
      assertThat(bounds).allMatch { it.right <= WINDOW_WIDTH.value }
    }
  }

  @Test fun `clicking an object inside a tab moves that tab rather than opening one`() {
    diveUiTest {
      openHeapDump()

      clickTheArray()

      // Which is what makes a tab something you read in: following what holds what would be a tab a click
      // if every step opened one, and the back arrow is what undoes a step.
      waitUntilAtLeastOneExists(hasText(tabTitleOfTheArray()), OPEN_TIMEOUT_MILLIS)
      assertThat(tabs().fetchSemanticsNodes()).hasSize(1)
    }
  }

  @Test fun `a tab is named after the object it is on`() {
    diveUiTest {
      openHeapDump()

      clickTheArray()

      // Class and address, because a strip of a dozen instances of one class is only one you can pick out
      // of if each tab says which instance it is.
      waitUntilAtLeastOneExists(hasText(tabTitleOfTheArray()), OPEN_TIMEOUT_MILLIS)
      tab(tabTitleOfTheArray()).assertIsDisplayed()
    }
  }

  @Test fun `a tab opened from the view is named without reading the heap dump for it`() {
    diveUiTest {
      openHeapDump()
      // The window's first tab is named by a read of its own, there being no view yet to have drawn it.
      val readsBefore = logged.count { it.startsWith(NAMING_READ) }

      middleClickTheArray()

      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { tabs().fetchSemanticsNodes().size == 2 }
      tab(tabTitleOfTheArray()).assertIsDisplayed()
      // Named from the rectangle that was clicked, which the view labelled when it laid the tree out. A
      // read to name it would land a beat after the tab is on the strip, however small a read it is, and
      // the placeholder it replaces is what someone watching sees as the tab flickering.
      assertThat(logged.filter { it.startsWith(NAMING_READ) }).hasSize(readsBefore)
    }
  }

  @Test fun `middle clicking an object opens it in a tab behind the one being read`() {
    diveUiTest {
      openHeapDump()

      middleClickTheArray()

      // Behind rather than in front: opening a tab this way is parking somewhere to come back to, so the
      // tab being read stays the one on screen and the whole heap dump is still what the panes describe.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { tabs().fetchSemanticsNodes().size == 2 }
      tab(HeapDominatorTreemap.ROOT_LABEL).assertIsSelected()
      tab(tabTitleOfTheArray()).assertIsNotSelected()
    }
  }

  @Test fun `closing the last tab leaves the heap dump open with nothing shown`() {
    diveUiTest {
      openHeapDump()

      onNodeWithText(CLOSE_TAB).performClick()

      // The dump stays read: it cost seconds to open, and the bar above is one click from a tab again.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { tabs().fetchSemanticsNodes().isEmpty() }
      onNodeWithText(NO_TAB_OPEN).assertIsDisplayed()
      screenButton(Place.LEAKS_LABEL).assertIsDisplayed()
    }
  }

  @Test fun `right clicking a tab copies a link to where that tab is`() {
    val copied = mutableListOf<String>()
    diveUiTest {
      val heapDumpFile = openHeapDump(copyToClipboard = { copied += it })

      tab(HeapDominatorTreemap.ROOT_LABEL).performMouseInput { rightClick() }
      onNodeWithText(COPY_LINK).performClick()

      // The heap dump, so that the link outlives this window, and this window with it, because the same
      // dump open twice is two places to be — plus the tab's own place, so that following it lands where
      // it was copied from. See [DeepLink].
      assertThat(copied).containsExactly(
        DeepLink(heapDumpFile, Place.wholeHeapDump()).toUri()
      )
    }
  }

  @Test fun `a tab copied and followed leads back to the object it was on`() {
    val copied = mutableListOf<String>()
    diveUiTest {
      openHeapDump(copyToClipboard = { copied += it })
      clickTheArray()
      waitUntilAtLeastOneExists(hasText(tabTitleOfTheArray()), OPEN_TIMEOUT_MILLIS)

      tab(tabTitleOfTheArray()).performMouseInput { rightClick() }
      onNodeWithText(COPY_LINK).performClick()

      // Where a link is copied from is wherever the tab has been moved to, not where it opened: a tab is
      // read in, and the object worth sending someone is the one being looked at when they are sent it.
      assertThat(DeepLink.parse(copied.single()).place).isEqualTo(Place.Object(payloadObjectId))
    }
  }

  @Test fun `right clicking a button on the bar copies a link to the screen it opens`() {
    val copied = mutableListOf<String>()
    diveUiTest {
      val heapDumpFile = openHeapDump(copyToClipboard = { copied += it })

      screenButton(Place.LEAKS_LABEL).performMouseInput { rightClick() }
      onNodeWithText(COPY_LINK).performClick()

      // A button opens a screen nobody has been to yet, and a link to it is that screen as it opens: no
      // tab has to be opened first to have something to copy.
      assertThat(copied).containsExactly(
        DeepLink(heapDumpFile, Place.Leaks()).toUri()
      )
      // And nothing was opened by asking for the link, which a menu that clicked the button would have.
      assertThat(tabs().fetchSemanticsNodes()).hasSize(1)
    }
  }

  @Test fun `a link opens the place it names in a tab of its own, in front`() {
    diveUiTest {
      var asked by mutableStateOf(emptyList<Place>())
      openHeapDump(linkedPlaces = { asked }, onLinkedPlaceOpened = { asked = asked - it })

      asked = listOf(Place.Starred)

      // Always another tab, never the one open: a link is somewhere else to look, and closing what
      // someone was reading to show them it is the one thing a link should not do.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { tabs().fetchSemanticsNodes().size == 2 }
      tab(Place.STARRED_LABEL).assertIsSelected()
      // And taken as it opens, so that the next frame doesn't open it again.
      assertThat(asked).isEmpty()
    }
  }

  /** Clicks the array, which covers almost the whole treemap, with the given mouse button. */
  private fun ComposeUiTest.clickTheArray(button: MouseButton = MouseButton.Primary) {
    val view = onNodeWithContentDescription(VIEW_DESCRIPTION).fetchSemanticsNode().boundsInRoot
    onRoot().performMouseInput {
      click(
        position = Offset(x = view.left + view.width * MAP_X, y = view.top + view.height * MAP_Y),
        button = button
      )
    }
  }

  private fun ComposeUiTest.middleClickTheArray() = clickTheArray(MouseButton.Tertiary)

  /** What the strip calls a tab open on the array: its class, then which array it is. */
  private fun tabTitleOfTheArray() = "Object[] ${hexObjectId(payloadObjectId)}"

  private fun ComposeUiTest.tabs(): SemanticsNodeInteractionCollection = onAllNodes(isTab())

  private fun ComposeUiTest.tab(title: String) = onNode(hasText(title) and isTab())

  private fun ComposeUiTest.screenButton(label: String) = onNode(hasText(label) and isButton())

  private fun isTab(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

  private fun isButton(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

  /** Opens a heap dump and hands back the file, which is what a link to a place in it names. */
  private fun ComposeUiTest.openHeapDump(
    /** Read inside the composition, so that a test can ask for a place once the window is up. */
    linkedPlaces: () -> List<Place> = { emptyList() },
    onLinkedPlaceOpened: (Place) -> Unit = {},
    copyToClipboard: (String) -> Unit = {}
  ): File {
    // Written before the composition rather than in it: every recomposition would write it again, and
    // the second one fails rather than returning the file the window is already reading.
    val heapDumpFile = testHeapDump()
    setContent {
      MaterialTheme {
        DiveApp(
          heapDumpFile = heapDumpFile,
          onHeapDumpChosen = { _, _ -> },
          deviceHeapDumps = DeviceHeapDumps(NO_DEVICE_ADB),
          linkedPlaces = linkedPlaces(),
          onLinkedPlaceOpened = onLinkedPlaceOpened,
          copyToClipboard = copyToClipboard
        )
      }
    }
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
    // A tab is named by a read of the heap dump, so a window whose strip has not caught up yet is one
    // where every assertion about a title would be about the placeholder.
    waitUntilAtLeastOneExists(hasText(HeapDominatorTreemap.ROOT_LABEL) and isTab(), OPEN_TIMEOUT_MILLIS)
    return heapDumpFile
  }

  /**
   * A heap dump where a single instance is the only path to a large object array, so that the array is
   * almost the whole treemap and can be clicked blind.
   */
  private fun testHeapDump(): File {
    val file = testFolder.newFile("heap.hprof")
    file.dump {
      val holder = "com.example.Holder" instance {
        val payload =
          ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
        payloadObjectId = payload.value
        field["payload"] = payload
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    return file
  }

  companion object {
    /** Somewhere in the middle of the view, which is inside whatever it draws biggest. */
    private const val MAP_X = 0.4f
    private const val MAP_Y = 0.6f

    private const val PAYLOAD_LENGTH = 2_000

    /** Opening a heap dump and laying the tree out both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** What the window logs when it has to read the heap dump to find out what to call a tab. */
    private const val NAMING_READ = "Reading what to call"

    /**
     * Enough tabs off the bar that they can't all fit across a window, whose width a UI test fixes at
     * [WINDOW_WIDTH]. A tab named after the object list is one of the narrower ones there is, so this is
     * comfortably past what a line holds rather than exactly it.
     */
    private const val TABS_PAST_ONE_LINE = 20

    /** An `adb` that answers as if nothing were plugged in, so no test here reaches a real device. */
    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }
  }
}
