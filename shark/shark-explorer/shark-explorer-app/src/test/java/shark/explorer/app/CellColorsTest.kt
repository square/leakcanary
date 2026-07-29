package shark.explorer.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.explorer.CellContent
import shark.explorer.CellSubject
import shark.explorer.PresentedCell
import shark.explorer.ReachabilityStrength
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.TreemapCell
import shark.explorer.TreemapRect

/**
 * Colours are computed from a cell's depth, and a heap dump nests as deep as it likes: a scheme that
 * ramps per level walks out of the range `Color.hsv` accepts and takes the window down with it, which
 * is what [a colour is defined at every depth of every scheme] is here to catch.
 */
class CellColorsTest {

  @Test fun `a colour is defined at every depth of every scheme`() {
    CellColorScheme.values().forEach { scheme ->
      (0..MUCH_DEEPER_THAN_ANY_HEAP_DUMP).forEach { depth ->
        ReachabilityStrength.values().forEach { strength ->
          colorOf(scheme, cell(depth = depth), strength)
        }
        colorOf(scheme, cell(depth = depth), strength = null)
      }
    }
  }

  @Test fun `a nested cell is lighter than the one holding it`() {
    val shades = (0..4).map { depth ->
      colorOf(CellColorScheme.DAISY, cell(depth = depth), STRONG).luminance()
    }

    assertThat(shades).isSorted
    assertThat(shades.first()).isLessThan(shades.last())
  }

  @Test fun `the ramp bottoms out rather than running off the end`() {
    val deep = colorOf(CellColorScheme.DAISY, cell(depth = 8), STRONG)
    val deeper = colorOf(CellColorScheme.DAISY, cell(depth = 80), STRONG)

    assertThat(deeper).isEqualTo(deep)
  }

  @Test fun `everything inside a top level block shares its colour`() {
    val firstBlock = cell(node = 1L, parent = ROOT, siblingIndex = 0, depth = 1)
    val secondBlock = cell(node = 2L, parent = ROOT, siblingIndex = 1, depth = 1)
    // Different sibling indexes under the same block: the hue comes from the block, not from where a
    // cell sits among its siblings.
    val nested = cell(node = 3L, parent = 1L, siblingIndex = 0, depth = 2)
    val alsoNested = cell(node = 4L, parent = 1L, siblingIndex = 7, depth = 2)
    val nestedInTheOther = cell(node = 5L, parent = 2L, siblingIndex = 0, depth = 2)
    val colors = CellColors.of(
      CellColorScheme.DAISY,
      listOf(firstBlock, secondBlock, nested, alsoNested, nestedInTheOther)
        .map { presented(it, STRONG) }
    )

    assertThat(colors.colorOf(presented(nested, STRONG)))
      .isEqualTo(colors.colorOf(presented(alsoNested, STRONG)))
    assertThat(colors.colorOf(presented(nestedInTheOther, STRONG)))
      .isNotEqualTo(colors.colorOf(presented(nested, STRONG)))
  }

  @Test fun `a cell standing for the siblings that did not fit is grey in every scheme`() {
    CellColorScheme.values().forEach { scheme ->
      val group = TreemapCell(
        subject = CellSubject.Group(parent = ROOT, nodeCount = 3),
        rect = RECT,
        depth = 2,
        weight = 100L
      )
      val color = colorOf(scheme, group, strength = null)

      assertThat(color.red).isEqualTo(color.green)
      assertThat(color.green).isEqualTo(color.blue)
    }
  }

  private fun colorOf(
    scheme: CellColorScheme,
    cell: TreemapCell<Long>,
    strength: ReachabilityStrength?
  ): Color {
    val presented = presented(cell, strength)
    return CellColors.of(scheme, listOf(presented)).colorOf(presented)
  }

  private fun presented(
    cell: TreemapCell<Long>,
    strength: ReachabilityStrength?
  ) = PresentedCell(
    cell,
    label = "node",
    content = strength?.let { CellContent.Object(it) } ?: CellContent.Leftover
  )

  private fun cell(
    node: Long = 1L,
    parent: Long? = ROOT,
    siblingIndex: Int = 0,
    depth: Int
  ) = TreemapCell(
    subject = CellSubject.Node(node = node, parent = parent, siblingIndex = siblingIndex),
    rect = RECT,
    depth = depth,
    weight = 100L
  )

  companion object {
    private const val ROOT = 0L

    /** The deepest heap dump seen so far nested 8 levels in the treemap. */
    private const val MUCH_DEEPER_THAN_ANY_HEAP_DUMP = 200

    private val RECT = TreemapRect(left = 0.0, top = 0.0, right = 100.0, bottom = 100.0)
  }
}
