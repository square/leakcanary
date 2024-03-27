package shark

class HeapGraphSequenceObjectGrowthDetector(
  private val heapGrowthDetector: HeapGraphObjectGrowthDetector
) : RepeatedObjectGrowthDetector {

  override fun findRepeatedlyGrowingObjects(
    heapGraphs: Sequence<CloseableHeapGraph>,
    scenarioLoopsPerGraph: Int
  ): GrowingObjectNodes {
    var i = 1
    var lastDiffResult: InputHeapTraversal = NoHeapTraversalYet
    for (heapGraph in heapGraphs) {
      val diffResult =
        heapGrowthDetector.findGrowingObjects(
          heapGraph, scenarioLoopsPerGraph, lastDiffResult
        )
      if (diffResult is HeapTraversalWithDiff) {
        val iterationCount = i * scenarioLoopsPerGraph
        SharkLog.d {
          "After $iterationCount (+ $scenarioLoopsPerGraph) iterations and heap dump $i: ${diffResult.growingNodes.size} growing nodes"
        }
        if (diffResult.growingNodes.isEmpty()) {
          return emptyList()
        }
      }
      lastDiffResult = diffResult
      i++
    }
    val finalDiffResult = lastDiffResult
    check(finalDiffResult is HeapTraversalWithDiff) {
      "finalDiffResult $finalDiffResult should be a HeapDiff as i ${i - 1} should be >= 2"
    }
    return finalDiffResult.growingNodes
  }
}
