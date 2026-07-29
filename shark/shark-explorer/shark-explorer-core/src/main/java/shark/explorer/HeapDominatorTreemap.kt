package shark.explorer

import shark.HeapGraph
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.ObjectDominators.DominatorNode
import shark.ObjectReporter
import shark.AndroidObjectInspectors
import shark.ValueHolder.Companion.NULL_REFERENCE

/**
 * A heap dump's dominator tree, seen as a [TreemapTree] weighted by retained size.
 *
 * Nodes are object ids, and the root is [NULL_REFERENCE]: the virtual root [shark.HeapDominatorTree]
 * puts above every GC root, so that the whole reachable heap is one rectangle.
 *
 * Which objects are in it depends on [followedStrengths] — see [HeapExplorer.treeFor]. With none of
 * them, an object only a weak reference points at is absent, because a weak reference retains nothing,
 * so the root doesn't add up to the size of the heap dump. [HeapSizes] is where the rest of the bytes
 * are accounted for.
 *
 * Reads the heap dump, so not from the UI thread. See [HeapExplorer].
 */
class HeapDominatorTreemap internal constructor(
  private val graph: HeapGraph,
  private val reachability: HeapReachability,
  private val nodes: Map<Long, DominatorNode>,
  /** The reference strengths this tree was built by following. */
  val followedStrengths: Set<ReachabilityStrength>
) : TreemapTree<Long> {

  override val root: Long get() = NULL_REFERENCE

  /** Bytes retained by [node]: its own shallow size plus that of everything it dominates. */
  override fun weight(node: Long): Long = nodes.getValue(node).retainedSize.toLong()

  override fun children(node: Long): List<Long> = nodes.getValue(node).dominatedObjectIds

  /** Whether [objectId] is in this tree, i.e. reachable by following [followedStrengths]. */
  operator fun contains(objectId: Long): Boolean = objectId in nodes

  /** How strongly the garbage collector holds on to [objectId]. */
  fun strengthOf(objectId: Long): ReachabilityStrength = if (objectId == root) {
    ReachabilityStrength.STRONG
  } else {
    reachability.strengthOf(objectId)
  }

  /**
   * A short name for [objectId], to draw on its rectangle.
   *
   * Cheap enough to call for every visible rectangle, unlike [summarize].
   */
  fun label(objectId: Long): String = if (objectId == root) {
    ROOT_LABEL
  } else {
    when (val heapObject = graph.findObjectById(objectId)) {
      is HeapClass -> "class ${heapObject.simpleName}"
      is HeapInstance -> heapObject.instanceClassSimpleName
      is HeapObjectArray -> heapObject.arrayClassSimpleName
      is HeapPrimitiveArray -> heapObject.arrayClassName
    }
  }

  /**
   * Everything the details panel shows about [objectId].
   *
   * Reads the object and runs Shark's object inspectors over it, so call it for the selected object
   * rather than for every rectangle.
   */
  fun summarize(objectId: Long): HeapObjectSummary {
    val node = nodes.getValue(objectId)
    val heapObject = if (objectId == root) null else graph.findObjectById(objectId)
    return HeapObjectSummary(
      objectId = objectId,
      label = label(objectId),
      className = when (heapObject) {
        null -> ROOT_LABEL
        is HeapClass -> heapObject.name
        is HeapInstance -> heapObject.instanceClassName
        is HeapObjectArray -> heapObject.arrayClassName
        is HeapPrimitiveArray -> heapObject.arrayClassName
      },
      strength = strengthOf(objectId),
      shallowSize = node.shallowSize,
      retainedSize = node.retainedSize.toLong(),
      retainedCount = node.retainedCount,
      dominatedObjectCount = node.dominatedObjectIds.size,
      inspectorLabels = if (heapObject == null) {
        emptyList()
      } else {
        val reporter = ObjectReporter(heapObject)
        AndroidObjectInspectors.appDefaults.forEach { it.inspect(reporter) }
        reporter.labels.toList()
      },
      stringValue = (heapObject as? HeapInstance)?.readAsJavaString()
    )
  }

  /**
   * Lays this tree out into [viewport] rooted at [root], and reads a label and a strength for every
   * rectangle: everything the UI needs to draw a treemap without touching the heap dump itself.
   */
  fun present(
    layout: TreemapLayout<Long>,
    viewport: TreemapRect,
    root: Long = this.root
  ): TreemapPresentation {
    val result = layout.layout(this, viewport, root)
    return TreemapPresentation(layout = result, cells = result.cells.map { it.presented() })
  }

  /** The same, laid out as rings around a centre rather than as rectangles. */
  fun presentRadial(
    layout: RadialLayout<Long>,
    viewport: TreemapRect,
    root: Long = this.root
  ): RadialPresentation {
    val result = layout.layout(this, viewport, root)
    return RadialPresentation(layout = result, cells = result.cells.map { it.presented() })
  }

  private fun <C : LayoutCell<Long>> C.presented(): PresentedCell<C> = when (val subject = subject) {
    is CellSubject.Node -> PresentedCell(
      cell = this,
      label = label(subject.node),
      strength = strengthOf(subject.node)
    )
    is CellSubject.Group -> PresentedCell(
      cell = this,
      label = "${subject.nodeCount} smaller objects",
      strength = null
    )
  }

  companion object {
    /**
     * The object id of the virtual root, which every dominator tree of a heap dump has, so the UI can
     * root its navigation there before it has a tree to ask.
     */
    const val ROOT_OBJECT_ID = NULL_REFERENCE

    /** What the virtual root above every GC root is called in the UI. */
    const val ROOT_LABEL = "All GC roots"
  }
}

/** What the UI knows about one heap object. See [HeapDominatorTreemap.summarize]. */
data class HeapObjectSummary(
  val objectId: Long,
  /** Short name, as drawn on a rectangle. */
  val label: String,
  /** Fully qualified class name, or array type. */
  val className: String,
  val strength: ReachabilityStrength,
  val shallowSize: Int,
  val retainedSize: Long,
  /** Number of objects retained, including this one. */
  val retainedCount: Int,
  /** Number of objects immediately dominated by this one, ie its children in the treemap. */
  val dominatedObjectCount: Int,
  /** What Shark's object inspectors have to say, e.g. that an activity is destroyed. */
  val inspectorLabels: List<String>,
  /** The content of a `java.lang.String` instance, null for anything else. */
  val stringValue: String?
)
