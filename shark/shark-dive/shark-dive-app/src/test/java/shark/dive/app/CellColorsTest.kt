package shark.dive.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.dive.CellContent
import shark.dive.CellSubject
import shark.dive.ObjectGroupKind
import shark.dive.PresentedCell
import shark.dive.ReachabilityStrength
import shark.dive.ReachabilityStrength.STRONG
import shark.dive.TreemapCell
import shark.dive.TreemapRect

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
          colorOf(coloring(scheme), cell(depth = depth), strength)
          colorOf(coloring(scheme, colored = false), cell(depth = depth), strength)
          colorOf(coloring(scheme), leftover(depth = depth), strength)
          colorOf(coloring(scheme), classGroup(depth = depth), strength)
        }
      }
    }
  }

  @Test fun `a nested cell is lighter than the one holding it`() {
    val shades = (0..4).map { depth ->
      colorOf(coloring(CellColorScheme.DAISY), cell(depth = depth), STRONG).luminance()
    }

    assertThat(shades).isSorted
    assertThat(shades.first()).isLessThan(shades.last())
  }

  @Test fun `the ramp bottoms out rather than running off the end`() {
    val deep = colorOf(coloring(CellColorScheme.DAISY), cell(depth = 8), STRONG)
    val deeper = colorOf(coloring(CellColorScheme.DAISY), cell(depth = 80), STRONG)

    assertThat(deeper).isEqualTo(deep)
  }

  @Test fun `everything inside a top level block shares its colour`() {
    // Depth 1 is a half of the heap dump, so the blocks worth telling apart are its children, at depth 2.
    val firstBlock = cell(node = 1L, parent = ROOT, siblingIndex = 0, depth = 2)
    val secondBlock = cell(node = 2L, parent = ROOT, siblingIndex = 1, depth = 2)
    // Different sibling indexes under the same block: the hue comes from the block, not from where a
    // cell sits among its siblings.
    val nested = cell(node = 3L, parent = 1L, siblingIndex = 0, depth = 3)
    val alsoNested = cell(node = 4L, parent = 1L, siblingIndex = 7, depth = 3)
    val nestedInTheOther = cell(node = 5L, parent = 2L, siblingIndex = 0, depth = 3)
    val colors = CellColors.of(
      coloring(CellColorScheme.DAISY),
      listOf(firstBlock, secondBlock, nested, alsoNested, nestedInTheOther)
        .map { presented(it, STRONG) }
    )

    assertThat(colors.colorOf(presented(nested, STRONG)))
      .isEqualTo(colors.colorOf(presented(alsoNested, STRONG)))
    assertThat(colors.colorOf(presented(nestedInTheOther, STRONG)))
      .isNotEqualTo(colors.colorOf(presented(nested, STRONG)))
  }

  @Test fun `a strength whose colour is switched off is grey, and nothing else is`() {
    CellColorScheme.values().forEach { scheme ->
      ReachabilityStrength.values().forEach { strength ->
        val muted = colorOf(coloring(scheme, colored = false), cell(depth = 2), strength)
        assertThat(muted.red).isEqualTo(muted.green)
        assertThat(muted.green).isEqualTo(muted.blue)

        // Grey says "switched off" only for as long as nothing that is on looks the same: a pile of
        // objects has a hue of its own, however washed out, and so has garbage.
        listOf(cell(depth = 2), leftover(depth = 2), classGroup(depth = 2)).forEach { cell ->
          val color = colorOf(coloring(scheme), cell, strength)
          assertThat(listOf(color.red, color.green, color.blue).distinct()).hasSizeGreaterThan(1)
        }
      }
    }
  }

  private fun colorOf(
    coloring: CellColoring,
    cell: TreemapCell<Long>,
    strength: ReachabilityStrength
  ): Color {
    val presented = presented(cell, strength)
    return CellColors.of(coloring, listOf(presented)).colorOf(presented)
  }

  private fun coloring(
    scheme: CellColorScheme,
    colored: Boolean = true
  ) = CellColoring(
    scheme = scheme,
    coloredStrengths = if (colored) ReachabilityStrength.values().toSet() else emptySet()
  )

  private fun presented(
    cell: TreemapCell<Long>,
    strength: ReachabilityStrength
  ) = PresentedCell(
    cell,
    label = "node",
    content = when (val subject = cell.subject) {
      is CellSubject.Group -> CellContent.Leftover(strength)
      is CellSubject.Own -> CellContent.Object(strength)
      is CellSubject.Node -> if (subject.node == CLASS_GROUP_NODE) {
        CellContent.ObjectGroup(ObjectGroupKind.CLASS, strength, objectCount = 3)
      } else {
        CellContent.Object(strength)
      }
    }
  )

  /** A cell standing for the siblings its parent's subdivision had no room for. */
  private fun leftover(depth: Int) = TreemapCell(
    subject = CellSubject.Group(parent = ROOT, nodeCount = 3),
    rect = RECT,
    depth = depth,
    weight = 100L
  )

  /** A cell standing for every instance of one class. */
  private fun classGroup(depth: Int) = cell(node = CLASS_GROUP_NODE, depth = depth)

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

    /** Which node [presented] gives a class group's content to. Negative, like the tree's group ids. */
    private const val CLASS_GROUP_NODE = -3L

    /** The deepest heap dump seen so far nested 8 levels in the treemap. */
    private const val MUCH_DEEPER_THAN_ANY_HEAP_DUMP = 200

    private val RECT = TreemapRect(left = 0.0, top = 0.0, right = 100.0, bottom = 100.0)
  }
}
