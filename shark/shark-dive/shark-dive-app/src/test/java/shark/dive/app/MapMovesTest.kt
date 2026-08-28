package shark.dive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.dive.HeapDominatorTreemap

/**
 * Where a click on the map takes the map.
 *
 * Every rectangle is a way in, the one standing for the siblings a subdivision had no room for included.
 * That one is no node of the tree, so there is nothing to root the map at but the rectangle they were left
 * out of — which is exactly where they are, and rooted there the map has the room to draw them one by one.
 *
 * And the way back to the top, since a map a few levels in has nothing on screen saying where the top is
 * until a rectangle has been clicked. [DiveAppTest] covers the rest of the window.
 */
@OptIn(ExperimentalTestApi::class)
class MapMovesTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  /** Every UI test here records what Shark logged. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  @Test fun `clicking the siblings that did not fit roots the map at what holds them`() {
    diveUiTest {
      // Every sibling weighs the same, so the rectangle standing for the ones left out weighs as much as
      // all of them together: it's the largest, and a squarified treemap puts that one in the top left.
      openHeapDump(testFolder.manySiblingsHeapDump())

      clickView(LEFTOVER_X, LEFTOVER_Y)

      waitUntilZoomedIn()
      // The panels stay on the pile rather than following the map to the array: the pile is what was
      // clicked, and it is the one thing on the map that the array's own details don't cover.
      onNodeWithText("Held by Object[]", substring = true).assertIsDisplayed()
    }
  }

  @Test fun `the screen bar opens a tab on the whole heap dump`() {
    diveUiTest {
      openHeapDump(testFolder.manySiblingsHeapDump())
      clickView(LEFTOVER_X, LEFTOVER_Y)
      waitUntilZoomedIn()

      // A tab of its own rather than this one moved: the bar is the way in to a heap dump, so clicking it
      // while reading an object is asking for both. Either way the map is drawn at the top again.
      onNode(hasText(HeapDominatorTreemap.ROOT_LABEL) and isButton()).performClick()

      waitUntilZoomedOut()
    }
  }

  /** Presses a point of the view given as a fraction of it, the view being one canvas. */
  private fun ComposeUiTest.clickView(
    xFraction: Float,
    yFraction: Float
  ) {
    val view = onNodeWithContentDescription(VIEW_DESCRIPTION).fetchSemanticsNode().boundsInRoot
    onRoot().performMouseInput {
      click(Offset(x = view.left + view.width * xFraction, y = view.top + view.height * yFraction))
    }
  }

  /**
   * Waits until the map has been laid out rooted somewhere other than the top of the tree, and until it is
   * back at the top.
   *
   * Read off the log, because nothing on screen says where the map is rooted: the view is one canvas, and
   * the panes beside it are about the rectangle clicked rather than about the node the map settled on.
   */
  private fun ComposeUiTest.waitUntilZoomedIn() {
    waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
      logged.any { it.startsWith(TREEMAP_LAID_OUT) && WHOLE_HEAP_DUMP_NODE !in it }
    }
  }

  private fun ComposeUiTest.waitUntilZoomedOut() {
    waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
      logged.lastOrNull { it.startsWith(TREEMAP_LAID_OUT) }?.contains(WHOLE_HEAP_DUMP_NODE) == true
    }
  }

  /**
   * A button of the screen bar, as against the tab and the chain row of the same name. See
   * [DiveAppTest], which spells out why the role is what tells the three apart.
   */
  private fun isButton(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

  private fun ComposeUiTest.openHeapDump(heapDumpFile: File) {
    setContent {
      MaterialTheme {
        var shown: File? by remember { mutableStateOf(heapDumpFile) }
        DiveApp(shown, onHeapDumpChosen = { file, _ -> shown = file })
      }
    }
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
  }

  companion object {
    /**
     * Well inside the largest rectangle of the second level, which is the one standing for the siblings
     * that didn't fit: every sibling weighs the same, so the ones left out weigh as much as all of them.
     */
    private const val LEFTOVER_X = 0.05f
    private const val LEFTOVER_Y = 0.45f

    /** Opening a heap dump and laying the tree out both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    private const val TREEMAP_LAID_OUT = "Read the treemap rooted at"
    private const val WHOLE_HEAP_DUMP_NODE = "the whole heap dump"
  }
}
