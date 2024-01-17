package shark

import shark.ReferencePattern.InstanceFieldPattern

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
   */
  val shortestPathTree: ShortestPathObjectNode

  companion object {

    /**
     * When running a heap growth analysis in the same process as where the scenario runs,
     * we should ignore the part of the graph used to keep track of the tree in the previous
     * iteration of the scenario.
     */
    val ignoredReferences: List<IgnoredReferenceMatcher>
      get() {
        val shortestPathNodeClass = ShortestPathObjectNode::class.java
        return shortestPathNodeClass.declaredFields.map { classField ->
          IgnoredReferenceMatcher(InstanceFieldPattern(shortestPathNodeClass.name, classField.name))
        }
      }
  }
}

class InitialHeapTraversal constructor(
  override val shortestPathTree: ShortestPathObjectNode
) : HeapTraversal

class HeapTraversalWithDiff(
  override val shortestPathTree: ShortestPathObjectNode,
  /**
   * Nodes that already existed in the previous traversal, still exist in this
   * [shortestPathTree], and have grown compared to the previous traversal.
   */
  val growingNodes: List<ShortestPathObjectNode>
) : HeapTraversal {
  override fun toString(): String {
    return "HeapTraversalWithDiff(growingNodes=\n$growingNodes"
  }
}
