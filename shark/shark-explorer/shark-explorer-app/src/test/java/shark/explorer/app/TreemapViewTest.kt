package shark.explorer.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.explorer.PresentedCell
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.TreemapCell
import shark.explorer.TreemapLayout
import shark.explorer.TreemapPresentation
import shark.explorer.TreemapRect
import shark.explorer.TreemapTree

/**
 * Covers the wiring between clicks on the canvas and the selection and zoom callbacks. The layout and
 * the hit testing themselves are unit tested in `shark-explorer-core`.
 *
 * The view is given exactly [VIEWPORT] pixels, so each test can lay a tree out itself and click the
 * middle of a rectangle it knows the position of.
 */
@OptIn(ExperimentalTestApi::class)
class TreemapViewTest {

  /** A root with a single child, so the child fills everything below the root's header. */
  private val oneChild = mapTree(ROOT to listOf(CHILD))

  private val leafRoot = mapTree(ROOT to emptyList())

  @Test fun `pressing a rectangle selects it`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val selected = mutableListOf<Long>()
      setContent { TreemapUnderTest(presentation, onSelectObject = { selected += it }) }

      onRoot().performMouseInput { click(presentation.centerOf(CHILD)) }

      assertThat(selected).containsExactly(CHILD)
    }
  }

  @Test fun `pressing a header selects the parent`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val selected = mutableListOf<Long>()
      setContent { TreemapUnderTest(presentation, onSelectObject = { selected += it }) }

      // The root keeps a header strip at the top for its own label, uncovered by its children.
      onRoot().performMouseInput { click(Offset(VIEWPORT.width.toFloat() / 2, 2f)) }

      assertThat(selected).containsExactly(ROOT)
    }
  }

  @Test fun `double clicking a rectangle zooms into it`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val zoomed = mutableListOf<Long>()
      setContent { TreemapUnderTest(presentation, onZoomInto = { zoomed += it }) }

      onRoot().performMouseInput { doubleClick(presentation.centerOf(CHILD)) }

      assertThat(zoomed).containsExactly(CHILD)
    }
  }

  @Test fun `a root without children fills the view on its own`() {
    runComposeUiTest {
      val presentation = leafRoot.present()
      val selected = mutableListOf<Long>()
      setContent { TreemapUnderTest(presentation, onSelectObject = { selected += it }) }

      onRoot().performMouseInput { click(presentation.centerOf(ROOT)) }

      assertThat(selected).containsExactly(ROOT)
    }
  }

  @Test fun `pressing the rectangle standing for the siblings that did not fit reports a group`() {
    runComposeUiTest {
      // More children than a node draws one by one, so the smallest ones end up in one rectangle.
      val presentation = mapTree(ROOT to (1L..500L).toList()).present()
      val groups = mutableListOf<TreemapCell.Group>()
      setContent { TreemapUnderTest(presentation, onSelectGroup = { groups += it }) }

      onRoot().performMouseInput { click(presentation.centerOfGroup()) }

      assertThat(groups.single().nodeCount).isEqualTo(300)
    }
  }

  @Test fun `nodes left out for lack of room are reported`() {
    runComposeUiTest {
      // Not enough room to subdivide anything, so even the root is left as it is.
      val presentation = oneChild.present(TreemapLayout(maxCells = 2))
      setContent { TreemapUnderTest(presentation) }

      onNodeWithText("1 node not expanded").assertIsDisplayed()
    }
  }

  private fun mapTree(vararg children: Pair<Long, List<Long>>): TreemapTree<Long> {
    val childrenByNode = children.toMap()
    return object : TreemapTree<Long> {
      override val root = ROOT
      override fun weight(node: Long) = 100L
      override fun children(node: Long) = childrenByNode[node] ?: emptyList()
    }
  }

  /** What [shark.explorer.HeapDominatorTreemap.present] does, for a tree that isn't a heap dump. */
  private fun TreemapTree<Long>.present(
    layout: TreemapLayout<Long> = TreemapLayout()
  ): TreemapPresentation {
    val result = layout.layout(this, VIEWPORT)
    return TreemapPresentation(
      layout = result,
      cells = result.cells.map { cell ->
        when (cell) {
          is TreemapCell.Node -> PresentedCell(cell, "node ${cell.node}", STRONG)
          is TreemapCell.Group -> PresentedCell(cell, "${cell.nodeCount} smaller objects", null)
        }
      }
    )
  }

  private fun TreemapPresentation.centerOf(node: Long): Offset =
    center(layout.cells.last { it is TreemapCell.Node && it.node == node }.rect)

  private fun TreemapPresentation.centerOfGroup(): Offset =
    center(layout.cells.filterIsInstance<TreemapCell.Group>().single().rect)

  private fun center(rect: TreemapRect) = Offset(
    ((rect.left + rect.right) / 2).toFloat(),
    ((rect.top + rect.bottom) / 2).toFloat()
  )

  companion object {
    private const val ROOT = 0L
    private const val CHILD = 1L

    private val VIEWPORT = TreemapRect(left = 0.0, top = 0.0, right = 600.0, bottom = 400.0)
  }
}

@Composable
private fun TreemapUnderTest(
  presentation: TreemapPresentation,
  onSelectObject: (Long) -> Unit = {},
  onSelectGroup: (TreemapCell.Group) -> Unit = {},
  onZoomInto: (Long) -> Unit = {}
) {
  MaterialTheme {
    var selected: Long? by remember { mutableStateOf(null) }
    val rect = presentation.layout.cells.first().rect
    val density = LocalDensity.current
    // Sized in pixels, matching the viewport the presentation was laid out in, so that a click at a
    // rectangle's coordinates lands on that rectangle.
    Box(
      Modifier.requiredSize(
        width = with(density) { rect.width.toFloat().toDp() },
        height = with(density) { rect.height.toFloat().toDp() }
      )
    ) {
      TreemapView(
        presentation = presentation,
        selected = selected,
        onSelectObject = {
          selected = it
          onSelectObject(it)
        },
        onSelectGroup = onSelectGroup,
        onZoomInto = onZoomInto
      )
    }
  }
}
