package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilExactlyOneExists
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.explorer.Adb
import shark.explorer.AdbOutput
import shark.explorer.DeepLink
import shark.explorer.DeviceHeapDumps
import shark.explorer.HeapObjectKind
import shark.explorer.Place
import shark.explorer.hexObjectId

/**
 * The screen listing every object of the heap dump, and the two ways in and out of it.
 *
 * The view a treemap can't be: a class with a thousand small instances is one line here and a thousand
 * rectangles too small to draw there. What it is about is that a row and a rectangle are the same object —
 * clicking either is the same move — and that the filter is how a heap dump of a hundred thousand objects
 * is one you can find something in. [ExplorerAppTest] covers the rest of the window.
 */
@OptIn(ExperimentalTestApi::class)
class ObjectsScreenTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  /** Every UI test here records what Shark logged. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  /** The object id of the array in [testHeapDump], recorded as the dump is written. */
  private var payloadObjectId = 0L

  @Test fun `every object of the heap dump can be listed`() {
    explorerUiTest {
      openHeapDump()

      listObjects()

      // One line per object, whatever its size, with the retained size the treemap draws its rectangle from.
      onNodeWithText("com.example.Holder instance").assertIsDisplayed()
      onNodeWithText("$PAYLOAD_LENGTH elements").assertIsDisplayed()
      // How much of the heap dump is being looked at, which is what says a search found little of it.
      onNodeWithText("objects match", substring = true).assertIsDisplayed()
    }
  }

  @Test fun `a few characters filter the list down to the class names holding them`() {
    explorerUiTest {
      openHeapDump()
      listObjects()

      // Part of a name rather than all of it, which is how anyone types a class they half remember.
      searchBox().performTextInput("Hold")

      waitUntilExactlyOneExists(hasText("com.example.Holder instance"), OPEN_TIMEOUT_MILLIS)
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        onAllNodesWithText("java.lang.Object[] array").fetchSemanticsNodes().isEmpty()
      }
    }
  }

  @Test fun `a class leads to the instances of it`() {
    explorerUiTest {
      openHeapDump()
      listObjects()
      // The class object itself, which the list has a line of its own for.
      onNodeWithText("com.example.Holder class").performClick()
      waitUntilAtLeastOneExists(hasText(LIST_INSTANCES), OPEN_TIMEOUT_MILLIS)

      onNodeWithText(LIST_INSTANCES).performClick()

      // Back on the list, filtered to the instances of that one class. Exactly it: a class whose name
      // merely contains this one is another class, and its instances are not these. Waited for by the class
      // itself going, since listing the objects again is a read of the heap dump and the list on screen is
      // the one from before it until it comes back.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        onAllNodesWithText("com.example.Holder class").fetchSemanticsNodes().isEmpty()
      }
      onNodeWithText("com.example.Holder instance").assertIsDisplayed()
      onNode(hasText(EXACT_MATCH) and isToggleable()).assertIsOn()
      kindToggle(HeapObjectKind.INSTANCE).assertIsOn()
      kindToggle(HeapObjectKind.CLASS).assertIsOff()
    }
  }

  @Test fun `clicking a listed object shows it on the map and describes it`() {
    explorerUiTest {
      openHeapDump()
      listObjects()

      onNodeWithText("java.lang.Object[] array").performClick()

      // The same place clicking its rectangle would have taken you, in the tab the list was read in: the
      // map rooted at it, and the panel describing it.
      waitUntilAtLeastOneExists(hasText(hexObjectId(payloadObjectId)), OPEN_TIMEOUT_MILLIS)
      onNodeWithContentDescription(VIEW_DESCRIPTION).assertIsDisplayed()
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        logged.any { it.startsWith(TREEMAP_LAID_OUT) && hexObjectId(payloadObjectId) in it }
      }
    }
  }

  @Test fun `a listed object can be opened in a tab of its own from its menu`() {
    explorerUiTest {
      openHeapDump()
      listObjects()

      onNodeWithText("java.lang.Object[] array").performMouseInput { rightClick() }
      onNodeWithText(OPEN_IN_NEW_TAB).performClick()

      // The gesture ⌘ clicking a row already had, spelled out in words: a row of a list is a way to an
      // object like a rectangle of the map, so it answers the same menu.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
          .fetchSemanticsNodes().size == 3
      }
    }
  }

  @Test fun `a listed object's menu copies a link to it`() {
    val copied = mutableListOf<String>()
    explorerUiTest {
      openHeapDump(copyToClipboard = { copied += it })
      listObjects()

      onNodeWithText("java.lang.Object[] array").performMouseInput { rightClick() }
      onNodeWithText(COPY_LINK).performClick()

      // Beside "open in a new tab" wherever that is, this row included: the two are the same thought a
      // step apart, and a link is how the object leaves this window at all.
      assertThat(copied)
        .containsExactly(DeepLink(WINDOW_ID, Place.Object(payloadObjectId)).toUri())
    }
  }

  private fun ComposeUiTest.openHeapDump(copyToClipboard: (String) -> Unit = {}) {
    val heapDumpFile = testHeapDump()
    setContent {
      MaterialTheme {
        ExplorerApp(
          heapDumpFile = heapDumpFile,
          deepLinkId = WINDOW_ID,
          copyToClipboard = copyToClipboard,
          // Nothing here opens a second heap dump, and which window one would land in is
          // `ExplorerWindowTest`'s.
          onHeapDumpChosen = { _, _ -> },
          // An `adb` connected to nothing, rather than the one on this machine: a test that shells out has
          // whatever devices happen to be plugged in to answer for.
          deviceHeapDumps = DeviceHeapDumps(NO_DEVICE_ADB)
        )
      }
    }
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
  }

  /** Opens a tab on the list and waits for the pass over the heap dump that fills it. */
  private fun ComposeUiTest.listObjects() {
    onNode(hasText(Place.OBJECTS_LABEL) and isButton()).performClick()
    waitUntilAtLeastOneExists(hasText("java.lang.Object[] array"), OPEN_TIMEOUT_MILLIS)
  }

  /** A button of the screen bar, as against the tab of the same name that clicking it opens. */
  private fun isButton(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

  /** The search box of the object list, the one thing in the window that takes typing. */
  private fun ComposeUiTest.searchBox(): SemanticsNodeInteraction = onNode(hasSetTextAction())

  /** The checkbox that filters a list of objects down to one kind of them. */
  private fun ComposeUiTest.kindToggle(kind: HeapObjectKind): SemanticsNodeInteraction =
    onNode(hasText(kind.displayName) and isToggleable())

  /**
   * A heap dump where a single instance is the only path to a large object array, so that the list has a
   * class, an instance and an array in it and the two sizes are worlds apart.
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
    /** Big enough that the array is most of the heap dump, and a round number to read in an assertion. */
    private const val PAYLOAD_LENGTH = 2_000

    /** Opening a heap dump, listing its objects and laying the tree out all happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    private const val TREEMAP_LAID_OUT = "Read the treemap rooted at"

    /** What a link copied here names this window by, fixed so that the copied link can be spelled out. */
    private const val WINDOW_ID = "abcd2345"

    /** An `adb` that answers as if nothing were plugged in, so no test here reaches a real device. */
    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }
  }
}
