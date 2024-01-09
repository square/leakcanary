package shark

import shark.DiffingHeapGrowthDetector.HeapDumpAfterLoopingScenario

class LoopingHeapGrowthDetector(
  private val heapGrowthDetector: DiffingHeapGrowthDetector
) {
  fun detectRepeatedHeapGrowth(
    heapDumps: Sequence<HeapDumpAfterLoopingScenario>
  ): HeapTraversalWithDiff {
    var i = 1
    var lastDiffResult: InputHeapTraversal = NoHeapTraversalYet
    for (heapDump in heapDumps) {
      val diffResult =
        heapGrowthDetector.detectHeapGrowth(heapDump, lastDiffResult)
      if (diffResult is HeapTraversalWithDiff) {
        val iterationCount = i * heapDump.scenarioLoopCount
        SharkLog.d {
          "After $iterationCount (+ ${heapDump.scenarioLoopCount}) iterations and heap dump $i: ${diffResult.growingNodes.size} growing nodes"
        }
        if (diffResult.growingNodes.isEmpty()) {
          return diffResult
        }
      }
      lastDiffResult = diffResult
      i++
    }
    val finalDiffResult = lastDiffResult
    check(finalDiffResult is HeapTraversalWithDiff) {
      "finalDiffResult $finalDiffResult should be a HeapDiff as i ${i - 1} should be >= 2"
    }
    return finalDiffResult
  }
}
