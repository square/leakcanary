package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.ReferenceHolder
import shark.dump

/**
 * The graph shape drawn in a window, which is where it has to be tested from: the other shapes are a
 * function of the tree and are driven a presentation at a time — `StackViewTest` and `RadialViewTest` —
 * but this one is a picture of what the reader has expanded, and what a circle holds is read off the
 * heap dump a click at a time. So the state under test is the window's, not the view's.
 *
 * The arranging and the hit testing are unit tested in `shark-explorer-core` (`GraphLayoutTest`,
 * `ObjectGraphTest`) and the panning and zooming in [GraphTransformTest]. What is only testable here is
 * that a click reaches the object under it and comes back as a bigger picture.
 *
 * Read off the log throughout, since the view is one canvas with no text of its own.
 */
@OptIn(ExperimentalTestApi::class)
class GraphViewTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  /** Every draw of the graph and every read behind one is a line in here. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  @Test fun `the same tree can be drawn as a graph of circles and arrows`() {
    explorerUiTest {
      openHeapDump()
      shapeOption(ViewShape.TREEMAP).assertIsSelected()

      shapeOption(ViewShape.GRAPH).performClick()

      shapeOption(ViewShape.GRAPH).assertIsSelected()
      // The whole heap dump is the circle it opens on, already expanded, with what the GC roots hold
      // drawn beside it.
      waitUntilTheGraphHasArrows()
    }
  }

  @Test fun `a circle of the graph is pressed to collapse it and pressed again to open it`() {
    explorerUiTest {
      openHeapDump()
      shapeOption(ViewShape.GRAPH).performClick()
      waitUntilTheGraphHasArrows()

      clickAt(graphRootCircle())

      // A click on this shape adds to the picture rather than moving the window, so pressing the circle
      // everything hangs off takes the lot off and leaves that one circle.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { logged.any { GRAPH_COLLAPSED in it } }

      clickAt(graphRootCircle())

      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        logged.count { GRAPH_DRAWN in it && GRAPH_COLLAPSED !in it } >= 2
      }
      // And putting it back read nothing: what was read for a circle is kept while it is collapsed.
      assertThat(logged.filter { it.startsWith(GRAPH_REFERENCES_READ) }).hasSize(1)
    }
  }

  /**
   * Waits until the graph has been drawn with something hanging off the circle it opens on.
   *
   * Two reads rather than one: the shape is arranged as soon as it is picked, and what the object it is
   * rooted at references arrives after that. So a view with no spinner over it is not yet a picture.
   */
  private fun ComposeUiTest.waitUntilTheGraphHasArrows() {
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
    waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
      logged.any { GRAPH_DRAWN in it && GRAPH_COLLAPSED !in it && ZERO_ARROWS !in it }
    }
  }

  /** The radio button for [shape] above the view. */
  private fun ComposeUiTest.shapeOption(shape: ViewShape): SemanticsNodeInteraction =
    onNode(hasText(shape.displayName) and isSelectable())

  private fun ComposeUiTest.clickAt(offset: Offset) {
    onRoot().performMouseInput { click(offset) }
  }

  private fun ComposeUiTest.openHeapDump() {
    val heapDumpFile = heapDump()
    setContent {
      MaterialTheme {
        var shown: File? by remember { mutableStateOf(heapDumpFile) }
        ExplorerApp(shown, onHeapDumpChosen = { file, _ -> shown = file })
      }
    }
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
  }

  /** An instance holding a large array, so that the circle the graph opens on has arrows off it. */
  private fun heapDump(): File {
    val file = testFolder.newFile("heap.hprof")
    file.dump {
      val holder = "com.example.Holder" instance {
        field["payload"] =
          ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    return file
  }

  companion object {
    /** Opening a heap dump and reading what a circle references both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** How the log says the graph was drawn, and what it says once the root has been collapsed. */
    private const val GRAPH_DRAWN = "Drew "
    private const val GRAPH_COLLAPSED = "Drew 1 circles and 0 arrows"

    /** What the very first draw says, before the read filling the picture in has come back. */
    private const val ZERO_ARROWS = "0 arrows"

    /** And how it says an expanded circle's references were read, which happens once per circle. */
    private const val GRAPH_REFERENCES_READ = "Read what the whole heap dump references"
  }
}
