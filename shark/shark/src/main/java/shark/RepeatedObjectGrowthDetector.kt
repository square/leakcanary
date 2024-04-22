package shark

interface RepeatedObjectGrowthDetector {

  /**
   * [heapGraphCount] The expected number of heap graphs returned by [heapGraphs], null if cannot tell.
   */
  fun findRepeatedlyGrowingObjects(
    heapGraphs: Sequence<CloseableHeapGraph>,
    scenarioLoopsPerGraph: Int,
    heapGraphCount: Int? = null
  ): GrowingObjectNodes
}
