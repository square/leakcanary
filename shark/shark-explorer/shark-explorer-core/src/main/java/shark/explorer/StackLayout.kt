package shark.explorer

import java.util.PriorityQueue

/**
 * A block placed by [StackLayout]: one row of the stack, as wide as its share of the heap.
 *
 * [rect] is the whole of it, so hit testing and drawing work off one value the way they do for a
 * treemap. Its top edge says the same thing [depth] does — every row is [StackLayoutResult.rowHeight]
 * tall, and a node's row is its depth — which is what makes a stack readable as a stack: the level a
 * block sits at is where it is on the screen, not how deeply it's nested in something else.
 */
data class StackCell<out N>(
  override val subject: CellSubject<N>,
  val rect: TreemapRect,
  override val depth: Int,
  override val weight: Long
) : LayoutCell<N>

/**
 * The result of laying a [TreemapTree] out as a stack of rows, one row per level.
 *
 * [cells] is ordered so that a node always precedes its descendants, matching
 * [TreemapLayoutResult.cells]. Nothing overlaps: a row is a level of its own and the blocks along one
 * are laid side by side, so the block at a point is the only block at that point.
 */
class StackLayoutResult<N>(
  val cells: List<StackCell<N>>,
  /** How many rows the blocks fill, which is the deepest level reached plus the root's own row. */
  val rowCount: Int,
  /** How tall one row is, in the pixels the viewport was measured in. */
  val rowHeight: Double,
  /** See [TreemapLayoutResult.truncatedNodeCount]. */
  val truncatedNodeCount: Int
) {

  /**
   * How tall everything laid out is, which is what a view of it has to scroll through: a stack is as
   * deep as the tree it drew, and that is regularly deeper than a viewport.
   */
  val contentHeight: Double get() = rowCount * rowHeight

  /** The block [point] falls in, or null where the stack has none: past its last row, or in a notch. */
  fun cellAt(point: TreemapPoint): StackCell<N>? = cells.firstOrNull { point in it.rect }
}

/**
 * Lays a [TreemapTree] out as a stack of rows — an icicle plot, the way a profiler draws a call tree
 * upside down: the node it's rooted at is the row across the top, its children are the row under it,
 * and so on downwards, each one as wide as its weight's share of the row above.
 *
 * **What it is for is the shape of one chain of domination.** A treemap and a ring both spend a
 * dimension on nesting, so a level is smaller than the level above it and the deep end of a chain is
 * where the pixels have run out. Here a level costs a row and nothing else: the twentieth dominator of
 * an object is as wide as the object's share of the heap, drawn at full height and named, however deep
 * it sits. What that costs instead is the whole picture at a glance — a stack is a tall thing to scroll
 * rather than a shape that fits a window, which is why the other two shapes are still the ones to
 * open on.
 *
 * Depth is bounded by [maxRows] rather than by the room a level gets, and width is what stops the
 * layout: a node is subdivided while its row is at least [minSubdivideWidth] wide, and children
 * narrower than [minDrawWidth], past [maxChildrenPerNode] — [maxRootChildren] for the row across the
 * top — or outside the [maxCells] budget become one [CellSubject.Group]. The widest block is
 * subdivided first so the budget buys visible detail.
 *
 * **A block's width is its share of the whole heap at every depth**, as in [TreemapLayout], and for
 * the same reason: children are given their share of what their parent *weighs*, not of what they add
 * up to. What the parent holds on its own is the width left over at the right end of the row, drawn as
 * a [CellSubject.Own] block, so a bitmap reads as a wide block with almost nothing under it and every
 * row of the stack can be compared with every other.
 */
class StackLayout<N>(
  /** How tall one row is. A row holds a line of text and nothing else, so this is a text height. */
  private val rowHeight: Double = 18.0,
  private val minSubdivideWidth: Double = 6.0,
  private val minDrawWidth: Double = 2.0,
  /**
   * How many rows to lay out at all, which is how far a view of this scrolls.
   *
   * A bound is needed because a row costs no width: a chain of single dominators is as wide at the
   * bottom as at the top, so nothing else would ever stop it, and a heap dump is free to hold a
   * hundred thousand of those in a linked list. Deeper than any chain a real dump has been measured to
   * have — 22 levels from an activity down to a list row on an 82 MB production dump — so what this
   * cuts off is the pathological rather than the interesting, and zooming into a node is how the rest
   * of it is reached.
   */
  private val maxRows: Int = 64,
  private val maxCells: Int = 5000,
  private val maxChildrenPerNode: Int = 200,
  /** And how many for the node the stack is rooted at, which has the whole width. See [TreemapLayout]. */
  private val maxRootChildren: Int = maxCells / 2
) {

  fun layout(
    tree: TreemapTree<N>,
    viewport: TreemapRect,
    /** The node to put across the top, which is what zooming changes. */
    root: N = tree.root
  ): StackLayoutResult<N> {
    val rootCell = StackCell(
      subject = CellSubject.Node(node = root, parent = null, siblingIndex = 0),
      rect = rowRect(viewport, depth = 0, left = viewport.left, width = viewport.width),
      depth = 0,
      weight = tree.weight(root)
    )
    val cells = mutableListOf(rootCell)
    if (viewport.width <= 0.0 || rowHeight <= 0.0) {
      return StackLayoutResult(cells, rowCount = 1, rowHeight = rowHeight, truncatedNodeCount = 0)
    }

    // Widest first, so that the budget is spent where a row has the room to be read. Ties broken by
    // insertion order to keep the layout deterministic.
    var insertionCount = 0L
    val pending = PriorityQueue<Pending<N>>(
      compareByDescending<Pending<N>> { it.cell.rect.width }.thenBy { it.insertionOrder }
    )
    pending += Pending(root, rootCell, insertionCount++)

    var truncatedNodeCount = 0

    while (pending.isNotEmpty()) {
      val (node, cell) = pending.poll()
      val childDepth = cell.depth + 1
      if (childDepth >= maxRows || cell.rect.width < minSubdivideWidth) {
        continue
      }

      // Only nodes with weight can be given a proportional width.
      val children = tree.children(node)
        .filter { tree.weight(it) > 0L }
        .sortedByDescending { tree.weight(it) }
      if (children.isEmpty()) {
        continue
      }

      // Three cells at the least: one child, the group standing for the rest and the node's own bytes.
      // Narrower rows keep being considered, so a node too wide to fit doesn't end the layout.
      val budget = maxCells - cells.size
      if (budget < 3) {
        truncatedNodeCount++
        continue
      }

      val weights = LongArray(children.size) { tree.weight(children[it]) }
      val childrenWeight = weights.sum()
      // What the row above is worth, which is what the row below shares out: the difference between the
      // two is what the node holds on its own, and it stays as width rather than being spread over the
      // children. Clamped because a tree is free to weigh a node by something other than the sum of
      // what's below it.
      val totalWeight = maxOf(cell.weight, childrenWeight)
      val drawnCount = minOf(
        drawableChildCount(weights, cell.rect.width, totalWeight),
        // Depth 0 is the node the stack is rooted at, and the only block on that row.
        if (cell.depth == 0) maxRootChildren else maxChildrenPerNode,
        budget - 2
      )

      var left = cell.rect.left
      var drawnWeight = 0L
      for (index in 0 until drawnCount) {
        val width = cell.rect.width * weights[index] / totalWeight
        val childCell = StackCell(
          subject = CellSubject.Node(node = children[index], parent = node, siblingIndex = index),
          rect = rowRect(viewport, childDepth, left, width),
          depth = childDepth,
          weight = weights[index]
        )
        cells += childCell
        pending += Pending(children[index], childCell, insertionCount++)
        left += width
        drawnWeight += weights[index]
      }

      // Then the children this row had no room for, as one block, and what the node holds itself at the
      // right end of the row. Both advance the row whether or not they're wide enough to draw, so that
      // what is drawn stays where its share of the width puts it.
      val groupedCount = children.size - drawnCount
      if (groupedCount > 0) {
        val groupWeight = childrenWeight - drawnWeight
        val width = cell.rect.width * groupWeight / totalWeight
        if (width >= minDrawWidth) {
          cells += StackCell(
            subject = CellSubject.Group(parent = node, nodeCount = groupedCount),
            rect = rowRect(viewport, childDepth, left, width),
            depth = childDepth,
            weight = groupWeight
          )
        }
        left += width
      }
      val ownWeight = totalWeight - childrenWeight
      if (ownWeight > 0) {
        val width = cell.rect.width * ownWeight / totalWeight
        if (width >= minDrawWidth) {
          cells += StackCell(
            subject = CellSubject.Own(node = node),
            rect = rowRect(viewport, childDepth, left, width),
            depth = childDepth,
            weight = ownWeight
          )
        }
      }
    }

    return StackLayoutResult(
      cells = cells,
      rowCount = cells.maxOf { it.depth } + 1,
      rowHeight = rowHeight,
      truncatedNodeCount = truncatedNodeCount
    )
  }

  /**
   * How many of [weights], which are sorted descending, are worth a block of their own along a row
   * [totalWidth] wide shared out in proportion to [totalWeight]. The weights only go down, so the ones
   * worth drawing are a prefix.
   */
  private fun drawableChildCount(
    weights: LongArray,
    totalWidth: Double,
    totalWeight: Long
  ): Int {
    if (totalWeight <= 0L) {
      return 0
    }
    var count = 0
    while (count < weights.size && totalWidth * weights[count] / totalWeight >= minDrawWidth) {
      count++
    }
    return count
  }

  /** One block's rectangle: [width] of the row [depth] rows down from the top of [viewport]. */
  private fun rowRect(
    viewport: TreemapRect,
    depth: Int,
    left: Double,
    width: Double
  ): TreemapRect {
    val top = viewport.top + depth * rowHeight
    return TreemapRect(left = left, top = top, right = left + width, bottom = top + rowHeight)
  }

  private data class Pending<N>(
    val node: N,
    val cell: StackCell<N>,
    val insertionOrder: Long
  )
}
