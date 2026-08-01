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
   *
   * A subdivided cell is covered by its own contents, so [edgeGrab] is how much of its border counts
   * as it rather than as what's inside: without that there is no way to point at a container at all,
   * since a node's children cover every pixel of it. So a container's outline wins over whatever
   * shares that edge, which is the line the view draws there, while the inside of a rectangle is
   * always the innermost thing at that point. Pointing at a gap in a subdivision — area left by
   * children too small to draw — lands on the node holding it.
   */
  fun cellAt(
    point: TreemapPoint,
    edgeGrab: Double = 0.0
  ): TreemapCell<N>? {
    var innermost: TreemapCell<N>? = null
    for (index in cells.indices.reversed()) {
      val cell = cells[index]
      if (point !in cell.rect) {
        continue
      }
      if (innermost == null) {
        innermost = cell
      }
      if (isSubdivided(cell) && cell.rect.isWithin(edgeGrab, point)) {
        return cell
      }
    }
    return innermost
  }

  /** Whether something is drawn inside [cell], so that none of its own area is left showing. */
  fun isSubdivided(cell: TreemapCell<N>): Boolean {
    val subject = cell.subject
    return subject is CellSubject.Node && subject.node in subdividedNodes
  }

  /** Filled in on the first hit test rather than during layout, which most layouts never need. */
  private val subdividedNodes: Set<N> by lazy {
    cells.mapNotNullTo(HashSet()) { cell ->
      when (val subject = cell.subject) {
        is CellSubject.Node -> subject.parent
        is CellSubject.Group -> subject.parent
        is CellSubject.Own -> subject.node
      }
    }
  }
}

/**
 * Lays a [TreemapTree] out into a viewport, choosing how deep to recurse from how much room each
 * node gets rather than from a fixed depth limit.
 *
 * A node is subdivided only when its rectangle is at least [minSubdivideWidth] by
 * [minSubdivideHeight]. Its children are drawn largest first, and those that would come out below
 * [minDrawSize], that are past [maxChildrenPerNode] or that don't fit in the remaining [maxCells]
 * budget become one [CellSubject.Group] instead. Subdivision happens largest rectangle first, so the
 * budget is spent where there is space to show detail.
 *
 * **A rectangle's area is its weight's share of the whole, at every depth.** A node's children cover
 * it exactly, and what it weighs on its own gets a [CellSubject.Own] cell rather than being spread
 * over them, so nesting costs no area at all and a node an eighth of the heap draws an eighth of the
 * viewport wherever it sits.
 *
 * That is the difference between finding a big object and not. A level used to cost an 18 dp header
 * strip for its label, so the chain from an activity down to a list row on a real app dump — 21 levels
 * — spent 378 dp of a 630 dp viewport on labels and left the bitmaps at the bottom a sliver
 * each. Now those levels cost nothing, and the bitmaps are the biggest things on the screen. What
 * that costs instead is that a subdivided rectangle has no room to put its own label in, so naming
 * the levels is the view's job rather than the layout's.
 *
 * The effect is that depth varies across the treemap, and that zooming into a node — passing it as
 * [layout]'s `root` so it gets the whole viewport — reveals structure too fine to draw from further
 * out rather than structure the layout refused to reach.
 */
class TreemapLayout<N>(
  private val minSubdivideWidth: Double = 12.0,
  private val minSubdivideHeight: Double = 12.0,
  private val minDrawSize: Double = 3.0,
  private val maxCells: Int = 5000,
  /**
   * How many children of a single node to draw one by one. The root of a real heap dump's dominator
   * tree has tens of thousands of children, and past a couple of hundred rectangles in one parent
   * there is nothing left to read.
   */
  private val maxChildrenPerNode: Int = 200
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

      // Three cells at the least: one child, the group standing for the rest and the node's own
      // weight. Smaller rectangles keep being considered, so a node too big to fit doesn't end the
      // layout.
      val budget = maxCells - cells.size
      if (budget < 3) {
        truncatedNodeCount++
        continue
      }

      val weights = LongArray(children.size) { tree.weight(children[it]) }
      val drawnCount = minOf(
        drawableChildCount(weights, rect.area, cell.weight),
        maxChildrenPerNode,
        budget - 2
      )
      val groupedCount = children.size - drawnCount
      var drawnWeight = 0L
      for (index in 0 until drawnCount) {
        drawnWeight += weights[index]
      }
      val groupWeight = weights.sum() - drawnWeight
      // What the node holds that isn't in a child: its shallow size, for a dominator tree. Clamped
      // because a tree is free to weigh a node by something other than the sum of what's below it.
      val ownWeight = (cell.weight - weights.sum()).coerceAtLeast(0L)

      val entries = ArrayList<Entry<N>>(drawnCount + 2)
      for (index in 0 until drawnCount) {
        entries += Entry(
          subject = CellSubject.Node(node = children[index], parent = node, siblingIndex = index),
          weight = weights[index],
          child = children[index]
        )
      }
      if (groupedCount > 0) {
        entries += Entry(CellSubject.Group(parent = node, nodeCount = groupedCount), groupWeight)
      }
      if (ownWeight > 0) {
        entries += Entry(CellSubject.Own(node = node), ownWeight)
      }
      // squarify() needs weights in decreasing order, and both cells that aren't children can
      // outweigh the smallest children drawn on their own. A stable sort, so layout stays
      // deterministic.
      entries.sortByDescending { it.weight }

      val rects = squarify(LongArray(entries.size) { entries[it].weight }, rect)
      val childDepth = cell.depth + 1
      entries.forEachIndexed { index, entry ->
        val childRect = rects[index]
        // A rectangle can still come out too thin to see despite having the area for it.
        if (childRect.width >= minDrawSize && childRect.height >= minDrawSize) {
          val childCell = TreemapCell(
            subject = entry.subject,
            rect = childRect,
            depth = childDepth,
            weight = entry.weight
          )
          cells += childCell
          entry.child?.let { pending += Pending(it, childCell, insertionCount++) }
        }
      }
    }

    return TreemapLayoutResult(cells, truncatedNodeCount)
  }

  /**
   * How many of [weights], which are sorted descending, are worth a rectangle of their own in an
   * area of [totalArea] shared out in proportion to [totalWeight].
   *
   * A node whose share of the area is under a [minDrawSize] square can't be seen or clicked whatever
   * shape it comes out as, and since the weights only go down, the ones worth drawing are a prefix.
   */
  private fun drawableChildCount(
    weights: LongArray,
    totalArea: Double,
    totalWeight: Long
  ): Int {
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

  /** A cell about to be placed: what it stands for, how much area it gets, and what to recurse into. */
  private class Entry<N>(
    val subject: CellSubject<N>,
    val weight: Long,
    /** Null for the cells that aren't a node of the tree, which can't be subdivided. */
    val child: N? = null
  )
}
