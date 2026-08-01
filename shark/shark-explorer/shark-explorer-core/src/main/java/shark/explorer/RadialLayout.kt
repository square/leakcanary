package shark.explorer

import java.util.PriorityQueue
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * An annular sector: the shape [RadialLayout] places, drawn as a slice of a ring.
 *
 * Angles are degrees, 0 at 3 o'clock and growing clockwise, which is what Compose's arc drawing takes.
 * The ring around the centre starts at 12 o'clock, so the first angle out of a layout is -90.
 */
data class RadialArc(
  val startAngle: Double,
  val sweepAngle: Double,
  val innerRadius: Double,
  val outerRadius: Double
) {

  /**
   * How long the sector runs along the middle of its ring.
   *
   * Stands in for how big it looks, the way an area does in a treemap: an arc of the same sweep is
   * bigger the further out it sits, and a sector thinner than a few pixels can't be seen or clicked
   * however wide its ring is.
   */
  val arcLength: Double get() = Math.toRadians(sweepAngle) * (innerRadius + outerRadius) / 2

  fun contains(
    radius: Double,
    angleDegrees: Double
  ): Boolean {
    if (radius < innerRadius || radius >= outerRadius) {
      return false
    }
    if (sweepAngle >= FULL_CIRCLE) {
      return true
    }
    val fromStart = (angleDegrees - startAngle) % FULL_CIRCLE
    return (if (fromStart < 0.0) fromStart + FULL_CIRCLE else fromStart) < sweepAngle
  }

  companion object {
    const val FULL_CIRCLE = 360.0
  }
}

/** An annular sector placed by [RadialLayout], standing for what its [subject] says. */
data class RadialCell<out N>(
  override val subject: CellSubject<N>,
  val arc: RadialArc,
  override val depth: Int,
  override val weight: Long
) : LayoutCell<N>

/**
 * The result of laying out a [TreemapTree] as rings around a centre.
 *
 * [cells] is ordered so that a node always precedes its descendants, matching
 * [TreemapLayoutResult.cells]. Unlike a treemap's rectangles, sectors never overlap: each ring is a
 * depth of its own.
 */
class RadialLayoutResult<N>(
  val cells: List<RadialCell<N>>,
  /** Where the rings are centred, in the coordinates of the viewport that was laid out. */
  val center: TreemapPoint,
  /** See [TreemapLayoutResult.truncatedNodeCount]. */
  val truncatedNodeCount: Int
) {

  /** The cell [point] falls in, or null if it's outside the outermost ring. */
  fun cellAt(point: TreemapPoint): RadialCell<N>? {
    val dx = point.x - center.x
    val dy = point.y - center.y
    val radius = hypot(dx, dy)
    val angle = Math.toDegrees(atan2(dy, dx))
    return cells.firstOrNull { it.arc.contains(radius, angle) }
  }
}

/**
 * Lays a [TreemapTree] out as a sunburst: the root is the disk in the middle, its children are the
 * ring around it, and so on outwards, each node taking the share of its parent's sweep that its weight
 * is of its siblings' — the same normalisation [TreemapLayout] does within a rectangle.
 *
 * Depth is adaptive the way it is in a treemap, only measured along a ring rather than as an area: a
 * node is subdivided while its sector is at least [minSubdivideArcLength] long, children shorter than
 * [minDrawArcLength], past [maxChildrenPerNode] — [maxRootChildren] for the node in the middle — or
 * outside the [maxCells] budget become one [CellSubject.Group], and the widest sector is subdivided
 * first so the budget buys visible detail. [ringCount] then bounds how far out the picture goes, and
 * zooming into a node is what reveals more.
 */
class RadialLayout<N>(
  /** How many rings around the centre disk. Deeper than that needs a zoom. */
  private val ringCount: Int = 8,
  private val minSubdivideArcLength: Double = 24.0,
  private val minDrawArcLength: Double = 3.0,
  private val maxCells: Int = 5000,
  private val maxChildrenPerNode: Int = 200,
  /** And how many for the node in the middle, which has the whole first ring. See [TreemapLayout]. */
  private val maxRootChildren: Int = maxCells / 2
) {

  fun layout(
    tree: TreemapTree<N>,
    viewport: TreemapRect,
    /** The node to put in the middle, which is what zooming changes. */
    root: N = tree.root
  ): RadialLayoutResult<N> {
    val center = TreemapPoint(
      x = (viewport.left + viewport.right) / 2,
      y = (viewport.top + viewport.bottom) / 2
    )
    // Square in a rectangular viewport: the rings have to fit the shorter side.
    val maxRadius = minOf(viewport.width, viewport.height) / 2
    val ringWidth = maxRadius / (ringCount + 1)
    val rootCell = RadialCell(
      subject = CellSubject.Node(node = root, parent = null, siblingIndex = 0),
      arc = RadialArc(
        startAngle = FIRST_ANGLE,
        sweepAngle = RadialArc.FULL_CIRCLE,
        innerRadius = 0.0,
        outerRadius = ringWidth
      ),
      depth = 0,
      weight = tree.weight(root)
    )
    val cells = mutableListOf(rootCell)
    if (maxRadius <= 0.0) {
      return RadialLayoutResult(cells, center, truncatedNodeCount = 0)
    }

    var insertionCount = 0L
    val pending = PriorityQueue<Pending<N>>(
      compareByDescending<Pending<N>> { it.cell.arc.sweepAngle }.thenBy { it.insertionOrder }
    )
    pending += Pending(root, rootCell, insertionCount++)

    var truncatedNodeCount = 0

    while (pending.isNotEmpty()) {
      val (node, cell) = pending.poll()
      if (cell.depth >= ringCount || cell.arc.arcLength < minSubdivideArcLength) {
        continue
      }

      // Only nodes with weight can be given a proportional sweep.
      val children = tree.children(node)
        .filter { tree.weight(it) > 0L }
        .sortedByDescending { tree.weight(it) }
      if (children.isEmpty()) {
        continue
      }

      // Two cells at the least: one child and the group standing for the rest.
      val budget = maxCells - cells.size
      if (budget < 2) {
        truncatedNodeCount++
        continue
      }

      val innerRadius = cell.arc.outerRadius
      val outerRadius = innerRadius + ringWidth
      val weights = LongArray(children.size) { tree.weight(children[it]) }
      val totalWeight = weights.sum()
      // How much of the ring this node's children have to share.
      val span = RadialArc(
        startAngle = cell.arc.startAngle,
        sweepAngle = cell.arc.sweepAngle,
        innerRadius = innerRadius,
        outerRadius = outerRadius
      )
      val drawnCount = minOf(
        drawableChildCount(weights, span.arcLength),
        // Depth 0 is the node the rings are drawn around, and the only cell there is.
        if (cell.depth == 0) maxRootChildren else maxChildrenPerNode,
        budget - 1
      )
      val childDepth = cell.depth + 1
      var startAngle = span.startAngle
      var drawnWeight = 0L
      for (index in 0 until drawnCount) {
        val sweepAngle = span.sweepAngle * weights[index] / totalWeight
        val child = children[index]
        val childCell = RadialCell(
          subject = CellSubject.Node(node = child, parent = node, siblingIndex = index),
          arc = RadialArc(startAngle, sweepAngle, innerRadius, outerRadius),
          depth = childDepth,
          weight = weights[index]
        )
        cells += childCell
        pending += Pending(child, childCell, insertionCount++)
        startAngle += sweepAngle
        drawnWeight += weights[index]
      }

      // Nothing to reorder for, unlike a treemap: sectors are laid along a ring, so the group can
      // simply take what's left of the sweep.
      val groupedCount = children.size - drawnCount
      if (groupedCount > 0) {
        val groupWeight = totalWeight - drawnWeight
        val groupArc = RadialArc(
          startAngle = startAngle,
          sweepAngle = span.sweepAngle * groupWeight / totalWeight,
          innerRadius = innerRadius,
          outerRadius = outerRadius
        )
        if (groupArc.arcLength >= minDrawArcLength) {
          cells += RadialCell(
            subject = CellSubject.Group(parent = node, nodeCount = groupedCount),
            arc = groupArc,
            depth = childDepth,
            weight = groupWeight
          )
        }
      }
    }

    return RadialLayoutResult(cells, center, truncatedNodeCount)
  }

  /**
   * How many of [weights], which are sorted descending, are worth a sector of their own along
   * [totalArcLength]. The weights only go down, so the ones worth drawing are a prefix.
   */
  private fun drawableChildCount(
    weights: LongArray,
    totalArcLength: Double
  ): Int {
    val totalWeight = weights.sum()
    if (totalWeight <= 0L) {
      return 0
    }
    var count = 0
    while (count < weights.size &&
      totalArcLength * weights[count] / totalWeight >= minDrawArcLength
    ) {
      count++
    }
    return count
  }

  private data class Pending<N>(
    val node: N,
    val cell: RadialCell<N>,
    val insertionOrder: Long
  )

  companion object {
    /** 12 o'clock, so that the largest child of the root starts where a clock hand would. */
    private const val FIRST_ANGLE = -90.0
  }
}
