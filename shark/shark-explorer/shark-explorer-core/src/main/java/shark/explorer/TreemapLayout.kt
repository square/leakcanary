package shark.explorer

import java.util.PriorityQueue

/**
 * The tree a [TreemapLayout] lays out, read lazily.
 *
 * A dominator tree has on the order of a million nodes and a treemap can usefully show a few
 * thousand, so [children] is only ever called for nodes the layout decides to subdivide. Building
 * the whole tree up front, as `leakcanary-app`'s treemap does, doesn't survive a real heap dump.
 */
interface TreemapTree<N> {
  val root: N

  /** The weight of [node] including everything it contains, e.g. a retained heap size in bytes. */
  fun weight(node: N): Long

  /** The children of [node], in any order. Empty for a leaf. */
  fun children(node: N): List<N>
}

/** A node placed by [TreemapLayout]. [depth] is 0 for the root. */
data class TreemapCell<N>(
  val node: N,
  val rect: TreemapRect,
  val depth: Int
)

/**
 * The result of laying out a [TreemapTree].
 *
 * [cells] is ordered so that a node always precedes its descendants, which is also back to front
 * draw order: drawing in order paints children over their parents.
 */
class TreemapLayoutResult<N>(
  val cells: List<TreemapCell<N>>,
  /**
   * The number of nodes that were not subdivided because [TreemapLayout.maxCells] was reached.
   * Non-zero means the treemap is showing less detail than the viewport had room for.
   */
  val truncatedNodeCount: Int
) {

  /**
   * The deepest cell containing [point], or null if the point is outside the treemap.
   *
   * The treemap is drawn into a single canvas, so there are no per-rectangle Compose nodes to hit
   * test against and this stands in for that. Kept here rather than in the UI so it can be unit
   * tested.
   */
  fun cellAt(point: TreemapPoint): TreemapCell<N>? = cells.lastOrNull { point in it.rect }
}

/**
 * Lays a [TreemapTree] out into a viewport, choosing how deep to recurse from how much room each
 * node gets rather than from a fixed depth limit.
 *
 * A node is subdivided only when its rectangle is at least [minSubdivideWidth] by
 * [minSubdivideHeight] — enough for a header plus a visible child — and children smaller than
 * [minDrawSize] on either side are dropped, since they can't be seen or clicked. Subdivision happens
 * largest rectangle first until [maxCells] is reached, so the detail budget is spent where there is
 * space to show it.
 *
 * The effect is that depth varies across the treemap, and that zooming into a node (laying it out
 * again as the root of a fresh viewport) is what reveals deeper structure.
 */
class TreemapLayout<N>(
  private val minSubdivideWidth: Double = 40.0,
  private val minSubdivideHeight: Double = 24.0,
  private val minDrawSize: Double = 3.0,
  private val maxCells: Int = 5000,
  /** Room reserved at the top of a subdivided rectangle for its own label. */
  private val headerHeight: Double = 18.0
) {

  fun layout(
    tree: TreemapTree<N>,
    viewport: TreemapRect
  ): TreemapLayoutResult<N> {
    val cells = mutableListOf(TreemapCell(tree.root, viewport, depth = 0))
    if (viewport.width <= 0.0 || viewport.height <= 0.0) {
      return TreemapLayoutResult(cells, truncatedNodeCount = 0)
    }

    // Subdivide the roomiest rectangle first so that the budget buys the most visible detail.
    // Ties broken by insertion order to keep the layout deterministic.
    var insertionCount = 0L
    val pending = PriorityQueue<Pending<N>>(
      compareByDescending<Pending<N>> { it.cell.rect.area }.thenBy { it.insertionOrder }
    )
    pending += Pending(cells.single(), insertionCount++)

    var truncatedNodeCount = 0

    while (pending.isNotEmpty()) {
      val cell = pending.poll().cell
      val rect = cell.rect
      if (rect.width < minSubdivideWidth || rect.height < minSubdivideHeight) {
        continue
      }

      // Only nodes with weight can be given proportional area.
      val children = tree.children(cell.node)
        .filter { tree.weight(it) > 0L }
        .sortedByDescending { tree.weight(it) }
      if (children.isEmpty()) {
        continue
      }

      // Subdivide a node either fully or not at all: a partial row reads as if the remainder were
      // empty. Smaller rectangles keep being considered, so a node too big to fit doesn't end the
      // layout.
      if (cells.size + children.size > maxCells) {
        truncatedNodeCount++
        continue
      }

      val inner = rect.inset(top = headerHeight)
      val rects = squarify(LongArray(children.size) { tree.weight(children[it]) }, inner)
      val childDepth = cell.depth + 1
      for ((index, child) in children.withIndex()) {
        val childRect = rects[index]
        if (childRect.width < minDrawSize || childRect.height < minDrawSize) {
          continue
        }
        val childCell = TreemapCell(child, childRect, childDepth)
        cells += childCell
        pending += Pending(childCell, insertionCount++)
      }
    }

    return TreemapLayoutResult(cells, truncatedNodeCount)
  }

  private class Pending<N>(
    val cell: TreemapCell<N>,
    val insertionOrder: Long
  )
}
