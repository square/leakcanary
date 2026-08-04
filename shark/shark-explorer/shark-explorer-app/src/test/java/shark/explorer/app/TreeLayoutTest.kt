package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * How often the window lays the tree out, which is the most expensive thing it does: a layout reads the
 * heap dump for every label it draws, a second and more of it on a large dump, all in front of a spinner.
 *
 * So one view asked for is one layout. Two for the same view is that cost paid twice for a result that gets
 * thrown away, and none for a view that has changed is a window drawing something else. Counting the reads
 * is what says which of those happened — [ExplorerAppTest] covers what the window shows, this covers what
 * it did to show it.
 */
@OptIn(ExperimentalTestApi::class)
class TreeLayoutTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  /** Every layout is a line in here. See [RecordedLog] and [HeapDumpSession.read]. */
  @get:Rule val logged = RecordedLog()

  @Test fun `opening a heap dump lays the tree out once`() {
    explorerUiTest {
      openHeapDump()

      settleTheHeapDumpThread(ViewShape.TREEMAP)
    }

    assertThat(treeLayoutsOf(ViewShape.TREEMAP)).hasSize(1)
  }

  /**
   * The other half of it: laying out once is only right if what does need laying out again still does.
   * Rings are laid out to different thresholds than rectangles, so they are a view of their own to read
   * rather than the one already drawn.
   */
  @Test fun `switching shape lays the tree out once more`() {
    explorerUiTest {
      openHeapDump()

      shapeOption(ViewShape.RADIAL).performClick()
      settleTheHeapDumpThread(ViewShape.RADIAL)
    }

    assertThat(treeLayoutsOf(ViewShape.RADIAL)).hasSize(1)
    assertThat(treeLayoutsOf(ViewShape.TREEMAP)).hasSize(1)
  }

  /** And a stack, whose thresholds are its own again, so it is a view of its own again too. */
  @Test fun `switching to a stack lays the tree out once more`() {
    explorerUiTest {
      openHeapDump()

      shapeOption(ViewShape.STACK).performClick()
      settleTheHeapDumpThread(ViewShape.STACK)
    }

    assertThat(treeLayoutsOf(ViewShape.STACK)).hasSize(1)
    assertThat(treeLayoutsOf(ViewShape.TREEMAP)).hasSize(1)
  }

  /**
   * And the graph, which is the same rule from the other side: it is arranged from what the reader has
   * expanded rather than read from the tree, so switching to it lays the tree out no further. What it
   * does read is the object it is rooted at and what that references, which is not a view.
   */
  @Test fun `switching to the graph lays the tree out no further`() {
    explorerUiTest {
      openHeapDump()

      shapeOption(ViewShape.GRAPH).performClick()
      settleTheHeapDumpThread(ViewShape.GRAPH)
    }

    assertThat(treeLayoutsOf(ViewShape.GRAPH)).isEmpty()
    assertThat(treeLayoutsOf(ViewShape.TREEMAP)).hasSize(1)
  }

  /**
   * Waits for a heap dump read that isn't a layout, which is what makes counting the layouts sound rather
   * than a race: reads queue on the heap dump's one thread in the order they were asked for, so a layout
   * queued behind the one that drew what the window is showing is in the log by the time this comes back.
   *
   * Pointing at a cell is the read to wait for rather than clicking one, because a click goes to what it
   * landed on, and going somewhere lays the tree out again — which is the very thing being counted.
   *
   * Where the pointer goes depends on [shape], because a point that is a cell of one shape is empty in
   * another: the middle of the view is a rectangle and it is a ring, and it is well past the last row of a
   * stack three rows deep.
   */
  private fun ComposeUiTest.settleTheHeapDumpThread(shape: ViewShape) {
    val cell = when (shape) {
      ViewShape.TREEMAP, ViewShape.RADIAL -> {
        val view = onNodeWithContentDescription(VIEW_DESCRIPTION).fetchSemanticsNode().boundsInRoot
        Offset(view.left + view.width * CELL_X, view.top + view.height * CELL_Y)
      }
      ViewShape.STACK -> stackRow(CELL_ROW, CELL_X)
      // The circle the graph opens on: the middle of the view is empty until something has been
      // expanded, and this shape starts with one circle.
      ViewShape.GRAPH -> graphRootCircle()
    }
    onRoot().performMouseInput { hover(cell) }
    waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { logged.any { it.startsWith(HOVER_READ) } }
  }

  /** Every layout of the tree as [shape] the window has asked for, one line per read of the heap dump. */
  private fun treeLayoutsOf(shape: ViewShape): List<String> =
    logged.filter { it.startsWith("Reading the ${shape.displayName.lowercase()} rooted at") }

  /** The radio button for [shape] above the view. */
  private fun ComposeUiTest.shapeOption(shape: ViewShape): SemanticsNodeInteraction =
    onNode(hasText(shape.displayName) and isSelectable())

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

  /**
   * A heap dump where one instance is the only path to a large object array, so that the two rectangles
   * cover almost the whole view and a press in the middle of it lands on one of them whichever shape it
   * is drawn as.
   */
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
    /** Somewhere in the middle of the view, which is inside whatever it draws biggest. */
    /**
     * Just off the middle of the view, which is a cell of it drawn as rectangles or as rings.
     *
     * Nearer the middle than a treemap would need, because the rings are as wide as the view's shorter side
     * divided by how many of them the layout allows for: a tree three levels deep fills only the first few,
     * and the space past those belongs to no cell to hover.
     */
    private const val CELL_X = 0.45f
    private const val CELL_Y = 0.55f

    /** The row of a stack that holds a cell to point at, counting from the one across the top. */
    private const val CELL_ROW = 1

    private const val PAYLOAD_LENGTH = 4096

    /** Opening a heap dump and laying the tree out both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** How the log says the chain holding the cell under the pointer was read. */
    private const val HOVER_READ = "Read what holds"
  }
}
