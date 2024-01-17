package shark

interface RepeatedObjectGrowthDetector {

  fun findRepeatedlyGrowingObjects(
    heapGraphs: Sequence<CloseableHeapGraph>,
    scenarioLoopsPerGraph: Int
  ): GrowingObjectNodes
}
