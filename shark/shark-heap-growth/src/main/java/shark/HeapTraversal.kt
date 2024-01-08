package shark

sealed interface InputHeapTraversal
object NoHeapTraversalYet : InputHeapTraversal
sealed interface HeapTraversal : InputHeapTraversal {
  /**
   * A representation of the heap as a tree of shortest path from roots to each
   * object in the heap, where:
   * - object identity is lost
   * - objects are grouped by identical path into a single node
   * - Path element names are determined using the edge name to reach them (e.g. field name) and
   * the object class name.
   * - We only keep nodes that were new or growing in the previous traversal.
   */
  val shortestPathTree: ShortestPathNode

  /**
   * Whether this traversal yielded a [shortestPathTree] that grew compared to the previous
   * traversal.
   */
  val growing: Boolean
}

class InitialHeapTraversal constructor(
  override val shortestPathTree: ShortestPathNode
) : HeapTraversal {
  override val growing get() = true
}

class HeapTraversalWithDiff(
  override val shortestPathTree: ShortestPathNode,
  /**
   * Nodes that already existed in the previous traversal, still exist in this
   * [shortestPathTree], and have grown compared to the previous traversal.
   */
  val growingNodes: List<ShortestPathNode>
) : HeapTraversal {
  override val growing get() = growingNodes.isNotEmpty()
}
