package shark

import shark.ReferencePattern.Companion.instanceField

sealed interface HeapTraversalInput {
  val traversalCount: Int

  /**
   * How many times a scenario that might cause heap growth is repeated in between each
   * dump and traversal. This leads the traversal algorithm to only look at objects that are
   * growing at least [scenarioLoopsPerGraph] times since the previous traversal.
   */
  val scenarioLoopsPerGraph: Int

  /**
   * How many heap dumps will be traversed in total, null if the caller can't tell.
   *
   * Retained sizes are only useful for the last traversal and the one before it, which is what
   * their increase is computed against, and computing them costs an additional traversal of the
   * heap. A caller that knows how many heap dumps it will traverse lets the earlier traversals
   * skip that work.
   */
  val heapDumpCount: Int?
}

class InitialState(
  override val scenarioLoopsPerGraph: Int = DEFAULT_SCENARIO_LOOPS_PER_GRAPH,
  override val heapDumpCount: Int? = null,
) : HeapTraversalInput {
  override val traversalCount = 0

  init {
    check(scenarioLoopsPerGraph >= 1) {
      "There should be at least 1 scenario loop per heap dump"
    }
    check(heapDumpCount == null || heapDumpCount >= 1) {
      "heapDumpCount should be at least 1 or null, not $heapDumpCount"
    }
  }

  companion object {
    const val DEFAULT_SCENARIO_LOOPS_PER_GRAPH = 1
  }
}

sealed interface HeapTraversalOutput : HeapTraversalInput {
  /**
   * A representation of the heap as a tree of shortest path from roots to each
   * object in the heap, where:
   * - object identity is lost
   * - objects are grouped by identical path into a single node
   * - Path element names are determined using the node & edge name to reach them (e.g. class name
   * + field name) as well as the class name of the reached object.
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
          instanceField(
            className = shortestPathNodeClass.name,
            fieldName = classField.name
          ).ignored()
        }
      }
  }
}

class FirstHeapTraversal constructor(
  override val shortestPathTree: ShortestPathObjectNode,
  previousTraversal: InitialState
) : HeapTraversalOutput {
  override val traversalCount = 1
  override val scenarioLoopsPerGraph = previousTraversal.scenarioLoopsPerGraph
  override val heapDumpCount = previousTraversal.heapDumpCount
}

class HeapDiff(
  override val traversalCount: Int,
  override val shortestPathTree: ShortestPathObjectNode,
  /**
   * Nodes that already existed in the previous traversal, still exist in this
   * [shortestPathTree], and have grown compared to the previous traversal.
   */
  val growingObjects: GrowingObjectNodes,
  previousTraversal: HeapTraversalInput
) : HeapTraversalOutput {

  val isGrowing: Boolean get() = growingObjects.isNotEmpty()

  override val scenarioLoopsPerGraph = previousTraversal.scenarioLoopsPerGraph
  override val heapDumpCount = previousTraversal.heapDumpCount

  override fun toString(): String {
    return "HeapGrowthTraversal(traversal=$traversalCount, " +
      "isGrowing=$isGrowing, " +
      "scenarioLoopsPerGraph=$scenarioLoopsPerGraph, " +
      "growingNodes=\n${growingObjects.joinToString("\n")}\n" +
      ")"
  }

  companion object
}
