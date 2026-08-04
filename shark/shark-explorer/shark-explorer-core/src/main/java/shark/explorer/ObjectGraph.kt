package shark.explorer

/**
 * The part of a heap dump's object graph that has been expanded so far: one object at the root, and
 * whatever the reader has since opened up below it.
 *
 * Unlike the three shapes of the dominator tree, which lay out whatever fits the viewport, this is
 * drawn one click at a time — so it is state rather than a layout, and it is here rather than in the
 * UI for the usual reason: expanding, collapsing and paging are then unit testable, and
 * [GraphLayout] is a pure function of this.
 *
 * Immutable, and replaced rather than mutated, so it can be held as UI state. Nothing is ever
 * dropped from [objectOf]: collapsing a node keeps what was read for it, so opening it again is
 * instant and costs no heap dump read. What *is* drawn is whatever [GraphLayout] can walk to from
 * the root through [referencesFrom], which is why a collapsed node takes its whole subtree off the
 * picture without anything having to delete it.
 *
 * A node whose references haven't been read yet is [pendingObjectId] rather than empty, so that a
 * click is instant and the heap dump read fills in behind it. See [HeapDominatorTreemap.referencesFrom].
 */
class ObjectGraph private constructor(
  /** The object every drawn reference hangs below, which is the node the view is rooted at. */
  val rootObjectId: Long,
  private val objects: Map<Long, GraphObject>,
  private val expanded: Set<Long>,
  private val read: Map<Long, ObjectReferences>,
  /**
   * How many pages of references each object has been asked for, where absent means one.
   *
   * An object can hold a million references and no picture is going to draw them, so an expansion
   * draws [REFERENCES_PER_PAGE] of them, heaviest first, with a cell saying how many were left out.
   * Pressing that cell is what raises this.
   */
  private val pageCounts: Map<Long, Int>
) {

  /** Nothing to draw: no heap dump open yet, or a view rooted at a node the tree doesn't have. */
  val isEmpty: Boolean get() = objects.isEmpty()

  /** What the circle for [objectId] says, or null for an object the graph hasn't read. */
  fun objectOf(objectId: Long): GraphObject? = objects[objectId]

  fun isExpanded(objectId: Long): Boolean = objectId in expanded

  /**
   * The references drawn as arrows leaving [objectId], heaviest first, and none for an object that
   * is collapsed or hasn't been read yet.
   */
  fun referencesFrom(objectId: Long): List<GraphReference> =
    if (objectId in expanded) read[objectId]?.references.orEmpty() else emptyList()

  /**
   * How many of [objectId]'s references aren't drawn, which is the cell hanging off it saying so,
   * and 0 while it is collapsed: a collapsed node draws nothing below it at all.
   */
  fun hiddenReferenceCountOf(objectId: Long): Int =
    if (objectId in expanded) read[objectId]?.hiddenCount ?: 0 else 0

  /** How many references to read for [objectId], which is one page more per press of its last cell. */
  fun referenceLimitOf(objectId: Long): Int = pageCountOf(objectId) * REFERENCES_PER_PAGE

  /**
   * An object that is expanded and whose references still have to be read, or null when the picture
   * is complete.
   *
   * One at a time, because reads queue on one thread anyway and because folding one in is what
   * uncovers the next: a caller reads this, reads the heap dump for it, and hands the answer to
   * [withReferences] until this comes back null.
   */
  val pendingObjectId: Long?
    get() = expanded.firstOrNull { objectId ->
      val references = read[objectId]
      // Read, but for fewer references than are now asked for, is pending again: pressing the cell
      // that counts what was left out is what puts a node back in this state.
      references == null ||
        (references.hiddenCount > 0 && references.references.size < referenceLimitOf(objectId))
    }

  /** Draws what [objectId] references below it, which its next read fills in. */
  fun expanding(objectId: Long): ObjectGraph =
    if (objectId in expanded) this else copy(expanded = expanded + objectId)

  /**
   * Takes what [objectId] references off the picture again.
   *
   * What was read for it is kept, so opening it again draws the same thing without reading the heap
   * dump: what a reader collapses is what they are done looking at, not what they were done with.
   */
  fun collapsing(objectId: Long): ObjectGraph =
    if (objectId in expanded) copy(expanded = expanded - objectId) else this

  /** Draws one more page of what [objectId] references, which its next read fills in. */
  fun showingMoreOf(objectId: Long): ObjectGraph =
    copy(pageCounts = pageCounts + (objectId to pageCountOf(objectId) + 1))

  /** Folds one read into the picture: the arrows leaving an object, and what they point at. */
  fun withReferences(references: ObjectReferences): ObjectGraph = copy(
    objects = objects + references.objects.associateBy { it.objectId },
    read = read + (references.fromObjectId to references)
  )

  private fun pageCountOf(objectId: Long): Int = pageCounts[objectId] ?: 1

  private fun copy(
    objects: Map<Long, GraphObject> = this.objects,
    expanded: Set<Long> = this.expanded,
    read: Map<Long, ObjectReferences> = this.read,
    pageCounts: Map<Long, Int> = this.pageCounts
  ) = ObjectGraph(rootObjectId, objects, expanded, read, pageCounts)

  companion object {

    /**
     * How many references one expansion draws, heaviest first.
     *
     * A `java.lang.Object[]` of a real app holds thousands, and a picture of thousands of circles is
     * no more readable than the pile of rectangles a treemap would draw — so what a node draws is the
     * heaviest of them, and what it leaves out is counted in a cell of its own rather than dropped.
     */
    const val REFERENCES_PER_PAGE = 24

    val EMPTY = ObjectGraph(
      rootObjectId = HeapDominatorTreemap.ROOT_OBJECT_ID,
      objects = emptyMap(),
      expanded = emptySet(),
      read = emptyMap(),
      pageCounts = emptyMap()
    )

    /**
     * A graph showing [root] alone, already expanded: a picture of one circle answers nothing, so
     * what the view is rooted at is opened up as it arrives.
     */
    fun rootedAt(root: GraphObject): ObjectGraph = ObjectGraph(
      rootObjectId = root.objectId,
      objects = mapOf(root.objectId to root),
      expanded = setOf(root.objectId),
      read = emptyMap(),
      pageCounts = emptyMap()
    )
  }
}

/**
 * One object of the heap dump as the graph draws it: a circle with the letter of its kind in it, and
 * what it is beside that.
 *
 * Less than a [HeapObjectSummary] on purpose. A summary runs Shark's object inspectors and reads
 * every field, which is a read worth doing for the one object a reader stopped on and not for the
 * two dozen an expansion draws at once — what those need is a name, a size and a shape.
 */
data class GraphObject(
  val objectId: Long,
  /** Fully qualified class name, or what a node standing for many objects is called. */
  val className: String,
  /**
   * Null for a node that stands for many objects rather than for one — the whole heap dump, the
   * uncollected garbage, a class the top of the tree gathers its instances under — which is drawn as
   * a hollow circle, the way a chain draws the rows above its objects.
   */
  val kind: HeapObjectKind?,
  val strength: ReachabilityStrength,
  val retainedSize: Long,
  val retainedCount: Int,
  /**
   * How many references it holds, which is what expanding it would draw.
   *
   * 0 says there is nothing under it, which is what lets the view draw a node as a dead end rather
   * than as something worth pressing.
   */
  val referenceCount: Int
)

/**
 * One reference of the heap dump as the graph draws it: an arrow from one circle to another, with
 * the field it is held in written on it.
 */
data class GraphReference(
  val fromObjectId: Long,
  val toObjectId: Long,
  /**
   * Which field of the object above points at it, and null where no field does: the children of a
   * node that stands for many objects hang off it because the dominator tree puts them there.
   */
  val reference: PathReference?,
  /**
   * Whether [fromObjectId] is the one node the dominator tree attributes [toObjectId]'s bytes to,
   * which is what makes this reference the reason that object is still in memory.
   *
   * The point of drawing a heap dump this way: a run of these from the node the view is rooted at is
   * exactly its dominated subtree, and everything else it points at is shared with something else.
   */
  val isDominator: Boolean
)

/** What one expansion read off the heap dump. See [HeapDominatorTreemap.referencesFrom]. */
data class ObjectReferences(
  val fromObjectId: Long,
  /** Heaviest first, one per object pointed at, at most the limit the read was asked for. */
  val references: List<GraphReference>,
  /** What those references point at, so that folding this in draws the circles as well as the arrows. */
  val objects: List<GraphObject>,
  /** How many more it holds than were read, which the view counts in a cell of its own. */
  val hiddenCount: Int
)
