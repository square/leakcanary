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
import shark.explorer.CellContent
import shark.explorer.CellSubject
import shark.explorer.LayoutCell
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
      val selected = mutableListOf<LayoutCell<Long>>()
      setContent { TreemapUnderTest(presentation, onSelect = { selected += it }) }

      onRoot().performMouseInput { click(presentation.centerOf(CHILD)) }

      assertThat(selected.map { it.node }).containsExactly(CHILD)
    }
  }

  @Test fun `pressing a header selects the parent`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val selected = mutableListOf<LayoutCell<Long>>()
      setContent { TreemapUnderTest(presentation, onSelect = { selected += it }) }

      // The root keeps a header strip at the top for its own label, uncovered by its children.
      onRoot().performMouseInput { click(Offset(VIEWPORT.width.toFloat() / 2, 2f)) }

      assertThat(selected.map { it.node }).containsExactly(ROOT)
    }
  }

  @Test fun `double clicking a rectangle zooms into it`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val zoomed = mutableListOf<List<Long>>()
      setContent { TreemapUnderTest(presentation, onZoomInto = { zoomed += it }) }

      onRoot().performMouseInput { doubleClick(presentation.centerOf(CHILD)) }

      assertThat(zoomed).containsExactly(listOf(CHILD))
    }
  }

  @Test fun `double clicking a nested rectangle reports every node down to it`() {
    runComposeUiTest {
      // Zooming has to leave a breadcrumb per dominator, so the whole chain below the current root is
      // reported rather than only the rectangle that was clicked.
      val presentation = mapTree(ROOT to listOf(PARENT), PARENT to listOf(CHILD)).present()
      val zoomed = mutableListOf<List<Long>>()
      setContent { TreemapUnderTest(presentation, onZoomInto = { zoomed += it }) }

      onRoot().performMouseInput { doubleClick(presentation.centerOf(CHILD)) }

      assertThat(zoomed).containsExactly(listOf(PARENT, CHILD))
    }
  }

  @Test fun `a root without children fills the view on its own`() {
    runComposeUiTest {
      val presentation = leafRoot.present()
      val selected = mutableListOf<LayoutCell<Long>>()
      setContent { TreemapUnderTest(presentation, onSelect = { selected += it }) }

      onRoot().performMouseInput { click(presentation.centerOf(ROOT)) }

      assertThat(selected.map { it.node }).containsExactly(ROOT)
    }
  }

  @Test fun `pressing the rectangle standing for the siblings that did not fit reports a group`() {
    runComposeUiTest {
      // More children than a node draws one by one, so the smallest ones end up in one rectangle.
      val presentation = mapTree(ROOT to (1L..500L).toList()).present()
      val selected = mutableListOf<LayoutCell<Long>>()
      setContent { TreemapUnderTest(presentation, onSelect = { selected += it }) }

      onRoot().performMouseInput { click(presentation.centerOfGroupUnder(ROOT)) }

      val group = selected.single().group
      assertThat(group.nodeCount).isEqualTo(300)
      assertThat(group.parent).isEqualTo(ROOT)
    }
  }

  @Test fun `each leftover rectangle is a selection of its own`() {
    runComposeUiTest {
      // Two subdivided nodes, each with more children than it draws: pressing one of the two leftover
      // rectangles has to select that one rather than every rectangle that looks like it.
      val presentation = mapTree(
        ROOT to listOf(PARENT, OTHER_PARENT),
        PARENT to (10L..14L).toList(),
        OTHER_PARENT to (20L..24L).toList()
      ).present(TreemapLayout(maxChildrenPerNode = 2))
      val selected = mutableListOf<LayoutCell<Long>>()
      setContent { TreemapUnderTest(presentation, onSelect = { selected += it }) }

      onRoot().performMouseInput { click(presentation.centerOfGroupUnder(PARENT)) }

      val group = selected.single().group
      assertThat(group.parent).isEqualTo(PARENT)
      assertThat(SelectedCell.of(group)).isNotEqualTo(
        SelectedCell.of(presentation.groupUnder(OTHER_PARENT).subject)
      )
      // Nor is a node ever selected by its own leftover rectangle.
      assertThat(SelectedCell.of(group)).isNotEqualTo(
        SelectedCell.of(presentation.nodeCellOf(PARENT).subject)
      )
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
        when (val subject = cell.subject) {
          is CellSubject.Node -> PresentedCell(cell, "node ${subject.node}", CellContent.Object(STRONG))
          is CellSubject.Group -> PresentedCell(
            cell,
            "${subject.nodeCount} smaller objects",
            CellContent.Leftover(STRONG)
          )
          is CellSubject.Own -> PresentedCell(
            cell,
            "node ${subject.node}",
            CellContent.Object(STRONG)
          )
        }
      }
    )
  }

  private fun TreemapPresentation.nodeCellOf(node: Long): TreemapCell<Long> =
    layout.cells.last { (it.subject as? CellSubject.Node)?.node == node }

  private fun TreemapPresentation.groupUnder(parent: Long): TreemapCell<Long> =
    layout.cells.single { (it.subject as? CellSubject.Group)?.parent == parent }

  /** What a cell stands for, for the cells a test already knows the kind of. */
  private val LayoutCell<Long>.node: Long get() = (subject as CellSubject.Node).node
  private val LayoutCell<Long>.group: CellSubject.Group<Long>
    get() = subject as CellSubject.Group

  private fun TreemapPresentation.centerOf(node: Long): Offset = center(nodeCellOf(node).rect)

  private fun TreemapPresentation.centerOfGroupUnder(parent: Long): Offset =
    center(groupUnder(parent).rect)

  private fun center(rect: TreemapRect) = Offset(
    ((rect.left + rect.right) / 2).toFloat(),
    ((rect.top + rect.bottom) / 2).toFloat()
  )

  companion object {
    private const val ROOT = 0L
    private const val CHILD = 1L
    private const val PARENT = 2L
    private const val OTHER_PARENT = 3L

    private val VIEWPORT = TreemapRect(left = 0.0, top = 0.0, right = 600.0, bottom = 400.0)
  }
}

@Composable
private fun TreemapUnderTest(
  presentation: TreemapPresentation,
  onSelect: (LayoutCell<Long>) -> Unit = {},
  onZoomInto: (List<Long>) -> Unit = {}
) {
  MaterialTheme {
    var selected: SelectedCell? by remember { mutableStateOf(null) }
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
        coloring = CellColoring.DEFAULT,
        selected = selected,
        onSelect = {
          selected = SelectedCell.of(it.subject)
          onSelect(it)
        },
        onZoomInto = onZoomInto
      )
    }
  }
}
