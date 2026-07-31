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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.explorer.HeapDominatorTreemap

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
    runComposeUiTest {
      openHeapDump()

      settleTheHeapDumpThread()
    }

    assertThat(treeLayoutsOf(ViewShape.TREEMAP)).hasSize(1)
  }

  /**
   * The other half of it: laying out once is only right if what does need laying out again still does.
   * Rings are laid out to different thresholds than rectangles, so they are a view of their own to read
   * rather than the one already drawn.
   */
  @Test fun `switching shape lays the tree out once more`() {
    runComposeUiTest {
      openHeapDump()

      shapeOption(ViewShape.RADIAL).performClick()
      settleTheHeapDumpThread()
    }

    assertThat(treeLayoutsOf(ViewShape.RADIAL)).hasSize(1)
    assertThat(treeLayoutsOf(ViewShape.TREEMAP)).hasSize(1)
  }

  /**
   * Waits for a heap dump read that isn't a layout, which is what makes counting the layouts sound rather
   * than a race: reads queue on the heap dump's one thread in the order they were asked for, so a layout
   * queued behind the one that drew what the window is showing is in the log by the time this comes back.
   *
   * Pressing a cell is the read to wait for, because the details panel filling in is the one that shows.
   */
  private fun ComposeUiTest.settleTheHeapDumpThread() {
    val view = onNodeWithContentDescription(VIEW_DESCRIPTION).fetchSemanticsNode().boundsInRoot
    onRoot().performMouseInput {
      click(Offset(view.left + view.width * CELL_X, view.top + view.height * CELL_Y))
    }
    waitUntilAtLeastOneExists(hasText("Retained objects"), OPEN_TIMEOUT_MILLIS)
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
        ExplorerApp(shown, onHeapDumpChosen = { shown = it })
      }
    }
    waitUntilAtLeastOneExists(
      hasText(HeapDominatorTreemap.ROOT_LABEL, substring = true),
      OPEN_TIMEOUT_MILLIS
    )
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
    private const val CELL_X = 0.4f
    private const val CELL_Y = 0.6f

    private const val PAYLOAD_LENGTH = 4096

    /** Opening a heap dump and laying the tree out both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L
  }
}
