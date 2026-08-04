package shark.explorer

/**
 * One circle of a laid out [ObjectGraph], with the name written beside it.
 *
 * A [LayoutCell] like a treemap's rectangle and a stack's block, so that everything downstream of the
 * geometry — which cell is selected, what the panels are asked to describe, what the card at the
 * pointer says — is the same code here as for the three shapes of the dominator tree.
 */
data class GraphNodeCell(
  override val subject: CellSubject<Long>,
  /**
   * The object the circle stands for, and null for the cell counting the references a node was not
   * drawn with, which is a [CellSubject.Group] and no object of the heap dump.
   */
  val drawn: GraphObject?,
  /** The middle of the circle. */
  val center: TreemapPoint,
  /** The circle and the name beside it, which together are what a click on the node has to hit. */
  val bounds: TreemapRect,
  /** How many references away from the node the graph is rooted at, which is also its column. */
  override val depth: Int,
  override val weight: Long,
  /** Whether what it references is drawn hanging off it. */
  val isExpanded: Boolean,
  /**
   * Whether it dominates any of the objects drawn hanging off it, which is `Dominates ↓` in a chain
   * and the same ring here.
   */
  val dominatesBelow: Boolean
) : LayoutCell<Long>

/**
 * What pressing [cell] draws: what an object references, or nothing where they were already drawn, or
 * one more page of them for the cell counting the ones a node had no room for.
 *
 * The one gesture the shape has, so it is here rather than in the view: which of the three a press is,
 * is a fact about the graph and is what a test of expanding and collapsing drives.
 */
fun ObjectGraph.pressing(cell: GraphNodeCell): ObjectGraph = when (val subject = cell.subject) {
  is CellSubject.Group -> showingMoreOf(subject.parent)
  is CellSubject.Node ->
    if (isExpanded(subject.node)) collapsing(subject.node) else expanding(subject.node)
  // No cell of this shape is an object's own bytes: a circle is the whole object, since nothing here is
  // drawn inside anything else.
  is CellSubject.Own -> this
}

/** One arrow of a laid out [ObjectGraph]: which reference, and between which two circles. */
data class GraphEdgeCell(
  val reference: GraphReference,
  /** The middle of the circle it leaves, which the view draws from the edge of. */
  val from: TreemapPoint,
  val to: TreemapPoint,
  /** How firmly the object it points at is held, which is what the line is drawn as. */
  val strength: ReachabilityStrength,
  /**
   * Whether the object it points at is drawn hanging off this one, or was already drawn elsewhere.
   *
   * A heap dump is a graph rather than a tree, so an object can be pointed at from several of the
   * circles on screen and can point back at one holding it. The layout hangs each object below the
   * first reference that reached it; every other arrow to it crosses the picture instead.
   */
  val isSpanning: Boolean
)

/**
 * An [ObjectGraph] with somewhere to draw each of its circles and arrows.
 *
 * [nodes] is ordered so that a node precedes everything hanging off it, matching every other layout
 * in this module.
 */
class GraphLayoutResult(
  /** Which object sits at the origin, which is what a view of it is moved back to the start by. */
  val rootObjectId: Long,
  val nodes: List<GraphNodeCell>,
  val edges: List<GraphEdgeCell>,
  /**
   * Everything laid out, which is what a view of it pans and zooms around.
   *
   * Around the origin rather than from it: the node the graph is rooted at sits at (0, 0), so top is
   * negative wherever anything was drawn above it. See [GraphLayout].
   */
  val bounds: TreemapRect
) {

  /** The circle [point] falls on, name included, or null where the picture has none. */
  fun cellAt(point: TreemapPoint): GraphNodeCell? = nodes.firstOrNull { point in it.bounds }

  companion object {
    val EMPTY = GraphLayoutResult(
      rootObjectId = HeapDominatorTreemap.ROOT_OBJECT_ID,
      nodes = emptyList(),
      edges = emptyList(),
      bounds = TreemapRect(0.0, 0.0, 0.0, 0.0)
    )
  }
}

/**
 * Lays an [ObjectGraph] out as a node-link diagram: the object it is rooted at on the left, what it
 * references in the column beside it, and so on rightwards for as far as the reader has expanded.
 *
 * The other three shapes divide a fixed viewport between everything under a node, so a level costs
 * area and how deep they go is decided for the reader. This one is the other trade: nothing is
 * divided, every circle is the same size however deep it sits, and how much is drawn is entirely what
 * was clicked. Which is what makes it the shape for the question the tree can't answer — *which*
 * reference holds this, and is it the only one — since a treemap draws where an object's bytes were
 * attributed and never how it is pointed at.
 *
 * **A heap dump is a graph, so the drawing is a tree over it plus the arrows that don't fit.** Each
 * object hangs below the first reference that reached it, depth first in the order its parent draws
 * them; a reference to an object already drawn elsewhere is an arrow across the picture and no second
 * circle. That is also what stops a cycle: an object is placed once.
 *
 * **The root sits at the origin**, and everything else is placed around it. So expanding a node moves
 * the picture rather than the root — a layout numbered from its top edge would slide the whole thing
 * up whenever something opened up above, which is exactly the object the reader was looking at.
 */
class GraphLayout(
  /** How far apart two columns are, which is a name's width plus room for an arrow. */
  private val columnWidth: Double = 220.0,
  /** How much room one circle and its name get down the picture. */
  private val rowHeight: Double = 44.0,
  private val nodeRadius: Double = 8.0,
  /** How wide the name beside a circle may be, which is the rest of what a click on a node hits. */
  private val labelWidth: Double = 170.0
) {

  fun layout(graph: ObjectGraph): GraphLayoutResult {
    if (graph.objectOf(graph.rootObjectId) == null) {
      return GraphLayoutResult.EMPTY
    }
    val placing = Placing(graph)
    placing.place(graph.rootObjectId, depth = 0, parent = null, siblingIndex = 0)
    // Around the root rather than from the top edge, so that what opens up above it leaves it where
    // the reader last saw it.
    val rootY = placing.yByObjectId.getValue(graph.rootObjectId)

    val nodes = placing.placedObjectIds.map { objectId ->
      val drawn = graph.objectOf(objectId)
      val depth = placing.depthByObjectId.getValue(objectId)
      nodeCell(
        subject = CellSubject.Node(
          node = objectId,
          parent = placing.parentByObjectId[objectId],
          siblingIndex = placing.siblingIndexByObjectId[objectId] ?: 0
        ),
        drawn = drawn,
        depth = depth,
        y = placing.yByObjectId.getValue(objectId) - rootY,
        weight = drawn?.retainedSize ?: 0L,
        isExpanded = graph.isExpanded(objectId),
        dominatesBelow = graph.referencesFrom(objectId).any { it.isDominator }
      )
    } + placing.leftovers.map { leftover ->
      nodeCell(
        subject = CellSubject.Group(parent = leftover.parentObjectId, nodeCount = leftover.nodeCount),
        drawn = null,
        depth = leftover.depth,
        y = leftover.y - rootY,
        weight = 0L,
        isExpanded = false,
        dominatesBelow = false
      )
    }
    val centerByObjectId = nodes.mapNotNull { cell ->
      (cell.subject as? CellSubject.Node)?.let { it.node to cell.center }
    }.toMap()

    val edges = placing.placedObjectIds.flatMap { objectId ->
      val from = centerByObjectId.getValue(objectId)
      graph.referencesFrom(objectId).mapNotNull { reference ->
        val to = centerByObjectId[reference.toObjectId] ?: return@mapNotNull null
        GraphEdgeCell(
          reference = reference,
          from = from,
          to = to,
          strength = graph.objectOf(reference.toObjectId)?.strength ?: ReachabilityStrength.STRONG,
          isSpanning = placing.parentByObjectId[reference.toObjectId] == objectId
        )
      }
    }
    return GraphLayoutResult(
      rootObjectId = graph.rootObjectId,
      nodes = nodes.sortedWith(compareBy({ it.depth }, { it.center.y })),
      edges = edges,
      bounds = nodes.map { it.bounds }.reduce { bounds, cell ->
        TreemapRect(
          left = minOf(bounds.left, cell.left),
          top = minOf(bounds.top, cell.top),
          right = maxOf(bounds.right, cell.right),
          bottom = maxOf(bounds.bottom, cell.bottom)
        )
      }
    )
  }

  private fun nodeCell(
    subject: CellSubject<Long>,
    drawn: GraphObject?,
    depth: Int,
    y: Double,
    weight: Long,
    isExpanded: Boolean,
    dominatesBelow: Boolean
  ): GraphNodeCell {
    val center = TreemapPoint(x = depth * columnWidth, y = y)
    return GraphNodeCell(
      subject = subject,
      drawn = drawn,
      center = center,
      // The name is written to the right of the circle, so that is where the rest of the target is.
      bounds = TreemapRect(
        left = center.x - nodeRadius,
        top = center.y - rowHeight / 2,
        right = center.x + labelWidth,
        bottom = center.y + rowHeight / 2
      ),
      depth = depth,
      weight = weight,
      isExpanded = isExpanded,
      dominatesBelow = dominatesBelow
    )
  }

  /**
   * Where each circle goes, worked out depth first from the root.
   *
   * A node with nothing under it takes the next row; a node with something under it is centred on
   * what it holds, between the first and the last of them. Which is the plainest tidy tree there is,
   * and it can't overlap: the rows a subtree takes are consecutive and a node's own row is inside
   * them, so two nodes in one column are always at least a row apart.
   */
  private inner class Placing(private val graph: ObjectGraph) {

    /** In the order they were reached, which puts a node before everything hanging off it. */
    val placedObjectIds = mutableListOf<Long>()
    val depthByObjectId = mutableMapOf<Long, Int>()
    val yByObjectId = mutableMapOf<Long, Double>()
    val parentByObjectId = mutableMapOf<Long, Long>()
    val siblingIndexByObjectId = mutableMapOf<Long, Int>()
    val leftovers = mutableListOf<Leftover>()

    private var nextRow = 0

    fun place(
      objectId: Long,
      depth: Int,
      parent: Long?,
      siblingIndex: Int
    ): Double {
      // Before recursing, so that a reference running back into this object is an arrow across the
      // picture rather than a second circle and a walk that never ends.
      placedObjectIds += objectId
      depthByObjectId[objectId] = depth
      siblingIndexByObjectId[objectId] = siblingIndex
      parent?.let { parentByObjectId[objectId] = it }

      val childYs = mutableListOf<Double>()
      graph.referencesFrom(objectId).forEach { reference ->
        val target = reference.toObjectId
        if (target !in depthByObjectId && graph.objectOf(target) != null) {
          childYs += place(target, depth + 1, parent = objectId, siblingIndex = childYs.size)
        }
      }
      val hiddenCount = graph.hiddenReferenceCountOf(objectId)
      if (hiddenCount > 0) {
        // Last of what hangs off the node, since it stands for the references that didn't make the
        // page: it takes a row like any other dead end, so the node above stays centred on the lot.
        val y = takeRow()
        leftovers += Leftover(objectId, depth + 1, y, hiddenCount)
        childYs += y
      }
      val y = if (childYs.isEmpty()) takeRow() else (childYs.first() + childYs.last()) / 2
      yByObjectId[objectId] = y
      return y
    }

    private fun takeRow(): Double = (nextRow++ + 0.5) * rowHeight
  }

  /** The references one node was not drawn with, as a cell of its own. See [CellSubject.Group]. */
  private class Leftover(
    val parentObjectId: Long,
    val depth: Int,
    val y: Double,
    val nodeCount: Int
  )
}
