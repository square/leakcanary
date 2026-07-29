package shark.explorer

import java.util.PriorityQueue

/**
 * The tree a layout lays out, read lazily.
 *
 * A dominator tree has on the order of a million nodes and a view can usefully show a few thousand,
 * so [children] is only ever called for nodes the layout decides to subdivide. Building the whole tree
 * up front, as `leakcanary-app`'s treemap does, doesn't survive a real heap dump.
 */
interface TreemapTree<N> {
  val root: N

  /** The weight of [node] including everything it contains, e.g. a retained heap size in bytes. */
  fun weight(node: N): Long

  /** The children of [node], in any order. Empty for a leaf. */
  fun children(node: N): List<N>
}

/** A rectangle placed by [TreemapLayout], standing for what its [subject] says. */
data class TreemapCell<out N>(
  override val subject: CellSubject<N>,
  val rect: TreemapRect,
  override val depth: Int,
  override val weight: Long
) : LayoutCell<N>

/**
 * The result of laying out a [TreemapTree] as a treemap.
 *
 * [cells] is ordered so that a node always precedes its descendants, which is also back to front
 * draw order: drawing in order paints children over their parents.
 */
class TreemapLayoutResult<N>(
  val cells: List<TreemapCell<N>>,
  /**
   * The number of nodes that had children but weren't subdivided at all because
   * [TreemapLayout.maxCells] was reached. Non-zero means the treemap is showing less detail than the
   * viewport had room for.
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

  /** See [nodePathTo]. */
  fun nodePathTo(cell: TreemapCell<N>): List<N> = cells.map { it.subject }.nodePathTo(cell.subject)
}

/**
 * Lays a [TreemapTree] out into a viewport, choosing how deep to recurse from how much room each
 * node gets rather than from a fixed depth limit.
 *
 * A node is subdivided only when its rectangle is at least [minSubdivideWidth] by
 * [minSubdivideHeight] — enough for a header plus a visible child. Its children are drawn largest
 * first, and those that would come out below [minDrawSize], that are past [maxChildrenPerNode] or
 * that don't fit in the remaining [maxCells] budget become one [CellSubject.Group] instead.
 * Subdivision happens largest rectangle first, so the budget is spent where there is space to show
 * detail.
 *
 * The effect is that depth varies across the treemap, and that zooming into a node — passing it as
 * [layout]'s `root` so it gets the whole viewport — is what reveals deeper structure.
 */
class TreemapLayout<N>(
  private val minSubdivideWidth: Double = 40.0,
  private val minSubdivideHeight: Double = 24.0,
  private val minDrawSize: Double = 3.0,
  private val maxCells: Int = 5000,
  /**
   * How many children of a single node to draw one by one. The root of a real heap dump's dominator
   * tree has tens of thousands of children, and past a couple of hundred rectangles in one parent
   * there is nothing left to read.
   */
  private val maxChildrenPerNode: Int = 200,
  /** Room reserved at the top of a subdivided rectangle for its own label. */
  private val headerHeight: Double = 18.0
) {

  fun layout(
    tree: TreemapTree<N>,
    viewport: TreemapRect,
    /** The node to fill [viewport] with, which is what zooming changes. */
    root: N = tree.root
  ): TreemapLayoutResult<N> {
    val rootCell = TreemapCell(
      subject = CellSubject.Node(node = root, parent = null, siblingIndex = 0),
      rect = viewport,
      depth = 0,
      weight = tree.weight(root)
    )
    val cells = mutableListOf(rootCell)
    if (viewport.width <= 0.0 || viewport.height <= 0.0) {
      return TreemapLayoutResult(cells, truncatedNodeCount = 0)
    }

    // Subdivide the roomiest rectangle first so that the budget buys the most visible detail.
    // Ties broken by insertion order to keep the layout deterministic.
    var insertionCount = 0L
    val pending = PriorityQueue<Pending<N>>(
      compareByDescending<Pending<N>> { it.cell.rect.area }.thenBy { it.insertionOrder }
    )
    pending += Pending(root, rootCell, insertionCount++)

    var truncatedNodeCount = 0

    while (pending.isNotEmpty()) {
      val (node, cell) = pending.poll()
      val rect = cell.rect
      if (rect.width < minSubdivideWidth || rect.height < minSubdivideHeight) {
        continue
      }

      // Only nodes with weight can be given proportional area.
      val children = tree.children(node)
        .filter { tree.weight(it) > 0L }
        .sortedByDescending { tree.weight(it) }
      if (children.isEmpty()) {
        continue
      }

      // Two cells at the least: one child and the group standing for the rest. Smaller rectangles
      // keep being considered, so a node too big to fit doesn't end the layout.
      val budget = maxCells - cells.size
      if (budget < 2) {
        truncatedNodeCount++
        continue
      }

      val inner = rect.inset(top = headerHeight)
      val weights = LongArray(children.size) { tree.weight(children[it]) }
      val drawnCount = minOf(
        drawableChildCount(weights, inner.area),
        maxChildrenPerNode,
        budget - 1
      )
      val groupedCount = children.size - drawnCount
      var drawnWeight = 0L
      for (index in 0 until drawnCount) {
        drawnWeight += weights[index]
      }
      val groupWeight = weights.sum() - drawnWeight

      // squarify() needs weights in decreasing order, and a group weighs as much as all the children
      // it stands for, which can be more than the smallest ones drawn on their own.
      val groupIndex = if (groupedCount == 0) {
        drawnCount
      } else {
        (0 until drawnCount).firstOrNull { weights[it] < groupWeight } ?: drawnCount
      }
      val cellCount = if (groupedCount == 0) drawnCount else drawnCount + 1
      val cellWeights = LongArray(cellCount) { index ->
        when {
          index < groupIndex -> weights[index]
          index == groupIndex -> groupWeight
          else -> weights[index - 1]
        }
      }

      val rects = squarify(cellWeights, inner)
      val childDepth = cell.depth + 1
      for (index in 0 until cellCount) {
        val childRect = rects[index]
        // A rectangle can still come out too thin to see despite having the area for it.
        if (childRect.width < minDrawSize || childRect.height < minDrawSize) {
          continue
        }
        if (index == groupIndex) {
          cells += TreemapCell(
            subject = CellSubject.Group(parent = node, nodeCount = groupedCount),
            rect = childRect,
            depth = childDepth,
            weight = groupWeight
          )
        } else {
          val childIndex = if (index < groupIndex) index else index - 1
          val child = children[childIndex]
          val childCell = TreemapCell(
            subject = CellSubject.Node(node = child, parent = node, siblingIndex = childIndex),
            rect = childRect,
            depth = childDepth,
            weight = cellWeights[index]
          )
          cells += childCell
          pending += Pending(child, childCell, insertionCount++)
        }
      }
    }

    return TreemapLayoutResult(cells, truncatedNodeCount)
  }

  /**
   * How many of [weights], which are sorted descending, are worth a rectangle of their own in an
   * area of [totalArea].
   *
   * A node whose share of the area is under a [minDrawSize] square can't be seen or clicked whatever
   * shape it comes out as, and since the weights only go down, the ones worth drawing are a prefix.
   */
  private fun drawableChildCount(
    weights: LongArray,
    totalArea: Double
  ): Int {
    val totalWeight = weights.sum()
    if (totalWeight <= 0L) {
      return 0
    }
    val minArea = minDrawSize * minDrawSize
    var count = 0
    while (count < weights.size && totalArea * weights[count] / totalWeight >= minArea) {
      count++
    }
    return count
  }

  private data class Pending<N>(
    val node: N,
    val cell: TreemapCell<N>,
    val insertionOrder: Long
  )
}
