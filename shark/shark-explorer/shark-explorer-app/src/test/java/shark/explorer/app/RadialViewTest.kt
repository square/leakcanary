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
import kotlin.math.cos
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.explorer.CellContent
import shark.explorer.CellSubject
import shark.explorer.LayoutCell
import shark.explorer.PresentedCell
import shark.explorer.RadialCell
import shark.explorer.RadialLayout
import shark.explorer.RadialPresentation
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.TreemapRect
import shark.explorer.TreemapTree

/**
 * The same wiring [TreemapViewTest] covers, for the radial view: a press selects the sector under the
 * pointer, a double click zooms in along the dominators down to it. The layout and the hit testing are
 * unit tested in `shark-explorer-core`.
 */
@OptIn(ExperimentalTestApi::class)
class RadialViewTest {

  /** A root with a single child, so the child fills the whole ring around it. */
  private val oneChild = mapTree(ROOT to listOf(CHILD))

  @Test fun `pressing a sector selects it`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val selected = mutableListOf<LayoutCell<Long>>()
      setContent { RadialUnderTest(presentation, onSelect = { selected += it }) }

      onRoot().performMouseInput { click(presentation.middleOf(CHILD)) }

      assertThat(selected.map { it.node }).containsExactly(CHILD)
    }
  }

  @Test fun `pressing the middle selects the root`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val selected = mutableListOf<LayoutCell<Long>>()
      setContent { RadialUnderTest(presentation, onSelect = { selected += it }) }

      onRoot().performMouseInput { click(presentation.middleOf(ROOT)) }

      assertThat(selected.map { it.node }).containsExactly(ROOT)
    }
  }

  @Test fun `double clicking a sector reports every node down to it`() {
    runComposeUiTest {
      val presentation = mapTree(ROOT to listOf(PARENT), PARENT to listOf(CHILD)).present()
      val zoomed = mutableListOf<List<Long>>()
      setContent { RadialUnderTest(presentation, onZoomInto = { zoomed += it }) }

      onRoot().performMouseInput { doubleClick(presentation.middleOf(CHILD)) }

      assertThat(zoomed).containsExactly(listOf(PARENT, CHILD))
    }
  }

  @Test fun `pressing the sector standing for the siblings that did not fit reports a group`() {
    runComposeUiTest {
      // A ring holds far fewer sectors than a treemap holds rectangles, so this many children under
      // one node is already past what is worth drawing one by one.
      val presentation = mapTree(ROOT to (1L..50L).toList())
        .present(RadialLayout(maxChildrenPerNode = 10))
      val selected = mutableListOf<LayoutCell<Long>>()
      setContent { RadialUnderTest(presentation, onSelect = { selected += it }) }

      onRoot().performMouseInput { click(presentation.middleOfGroupUnder(ROOT)) }

      val group = selected.single().subject as CellSubject.Group
      assertThat(group.nodeCount).isEqualTo(40)
      assertThat(group.parent).isEqualTo(ROOT)
    }
  }

  @Test fun `pressing outside the rings selects nothing`() {
    runComposeUiTest {
      val presentation = oneChild.present(RadialLayout(ringCount = 2))
      val selected = mutableListOf<LayoutCell<Long>>()
      setContent { RadialUnderTest(presentation, onSelect = { selected += it }) }

      onRoot().performMouseInput { click(Offset(1f, 1f)) }

      assertThat(selected).isEmpty()
    }
  }

  @Test fun `nodes left out for lack of room are reported`() {
    runComposeUiTest {
      val presentation = oneChild.present(RadialLayout(maxCells = 2))
      setContent { RadialUnderTest(presentation) }

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

  /** What [shark.explorer.HeapDominatorTreemap.presentRadial] does, for a tree that isn't a heap dump. */
  private fun TreemapTree<Long>.present(
    layout: RadialLayout<Long> = RadialLayout()
  ): RadialPresentation {
    val result = layout.layout(this, VIEWPORT)
    return RadialPresentation(
      layout = result,
      cells = result.cells.map { cell ->
        when (val subject = cell.subject) {
          is CellSubject.Node -> PresentedCell(cell, "node ${subject.node}", CellContent.Object(STRONG))
          is CellSubject.Group -> PresentedCell(
            cell,
            "${subject.nodeCount} smaller objects",
            CellContent.Leftover(STRONG)
          )
        }
      }
    )
  }

  private val LayoutCell<Long>.node: Long get() = (subject as CellSubject.Node).node

  private fun RadialPresentation.middleOf(node: Long): Offset =
    middleOf(layout.cells.last { (it.subject as? CellSubject.Node)?.node == node })

  private fun RadialPresentation.middleOfGroupUnder(parent: Long): Offset =
    middleOf(layout.cells.single { (it.subject as? CellSubject.Group)?.parent == parent })

  private fun RadialPresentation.middleOf(cell: RadialCell<Long>): Offset {
    val arc = cell.arc
    val radius = (arc.innerRadius + arc.outerRadius) / 2
    val angle = Math.toRadians(arc.startAngle + arc.sweepAngle / 2)
    return Offset(
      x = (layout.center.x + radius * cos(angle)).toFloat(),
      y = (layout.center.y + radius * sin(angle)).toFloat()
    )
  }

  companion object {
    private const val ROOT = 0L
    private const val CHILD = 1L
    private const val PARENT = 2L

    private val VIEWPORT = TreemapRect(left = 0.0, top = 0.0, right = 600.0, bottom = 400.0)
  }
}

@Composable
private fun RadialUnderTest(
  presentation: RadialPresentation,
  onSelect: (LayoutCell<Long>) -> Unit = {},
  onZoomInto: (List<Long>) -> Unit = {}
) {
  MaterialTheme {
    var selected: SelectedCell? by remember { mutableStateOf(null) }
    val density = LocalDensity.current
    // Sized in pixels, matching the viewport the presentation was laid out in, so that a click at a
    // sector's coordinates lands on that sector.
    Box(
      Modifier.requiredSize(
        width = with(density) { 600f.toDp() },
        height = with(density) { 400f.toDp() }
      )
    ) {
      RadialView(
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
