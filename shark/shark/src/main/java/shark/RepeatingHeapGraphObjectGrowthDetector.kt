package shark

import shark.RepeatingHeapGraphObjectGrowthDetector.CompletionListener

/**
 * @see findRepeatedlyGrowingObjects
 */
class RepeatingHeapGraphObjectGrowthDetector(
  private val objectGrowthDetector: ObjectGrowthDetector,
  private val completionListener: CompletionListener = CompletionListener { }
) {

  fun interface CompletionListener {
    fun onObjectGrowthDetectionComplete(result: HeapDiff)
  }

  /**
   * Detects object growth by iterating through [heapGraphSequence] repeatedly until no object
   * growth is detected or the sequence ends. Returns the [HeapDiff] for the last
   * iteration. You can check [HeapDiff.isGrowing] and
   * [HeapDiff.growingObjects] to report object growth.
   */
  fun findRepeatedlyGrowingObjects(
    scenarioLoopsPerGraph: Int = InitialState.DEFAULT_SCENARIO_LOOPS_PER_GRAPH,
    heapGraphSequence: Sequence<CloseableHeapGraph>,
  ): HeapDiff {
    var lastTraversal: HeapTraversalInput = InitialState(scenarioLoopsPerGraph)
    for (heapGraph in heapGraphSequence) {
      val output = objectGrowthDetector.findGrowingObjects(
        heapGraph = heapGraph,
        previousTraversal = lastTraversal,
      )
      if (output is HeapDiff && !output.isGrowing) {
        completionListener.onObjectGrowthDetectionComplete(output)
        return output
      }
      lastTraversal = output
    }
    check(lastTraversal is HeapDiff) {
      "Final output should be a HeapGrowth, traversalCount ${lastTraversal.traversalCount - 1} " +
        "should be >= 2. Output: $lastTraversal"
    }
    completionListener.onObjectGrowthDetectionComplete(lastTraversal)
    return lastTraversal
  }
}
