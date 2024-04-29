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
   * The expected max number of traversals, or null if unknown.
   */
  val heapGraphCount: Int?
}

class InitialState(
  override val scenarioLoopsPerGraph: Int = 1,
  override val heapGraphCount: Int? = null
) : HeapTraversalInput {
  override val traversalCount = 0

  init {
    check(scenarioLoopsPerGraph >= 1) {
      "There should be at least 1 scenario loop per heap dump"
    }
    check(heapGraphCount == null || heapGraphCount >= 2) {
      "There should be at least 2 heap dumps to detect growing objects"
    }
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
  override val heapGraphCount: Int? = previousTraversal.heapGraphCount
}

class HeapGrowthTraversal(
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
  override val heapGraphCount: Int? = previousTraversal.heapGraphCount
  override fun toString(): String {
    val traversal = if (heapGraphCount != null) {
      "traversal=$traversalCount/$heapGraphCount"
    } else {
      "traversal=$traversalCount"
    }

    return "HeapGrowthTraversal($traversal, " +
      "isGrowing=$isGrowing, " +
      "scenarioLoopsPerGraph=$scenarioLoopsPerGraph, " +
      "growingNodes=\n${growingObjects.joinToString("\n")}\n" +
      ")"
  }
}
