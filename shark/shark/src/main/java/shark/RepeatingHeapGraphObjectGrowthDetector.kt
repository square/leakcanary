package shark

/**
 * @see findRepeatedlyGrowingObjects
 */
class RepeatingHeapGraphObjectGrowthDetector(
  private val objectGrowthDetector: ObjectGrowthDetector
) {

  /**
   * Detects object growth by iterating through [heapGraphSequence] repeatedly until no object
   * growth is detected or the sequence ends. Returns the [HeapGrowthTraversal] for the last
   * iteration. You can check [HeapGrowthTraversal.isGrowing] and
   * [HeapGrowthTraversal.growingObjects] to report object growth.
   */
  fun findRepeatedlyGrowingObjects(
    initialState: InitialState = InitialState(),
    heapGraphSequence: Sequence<CloseableHeapGraph>,
    ): HeapGrowthTraversal {
    var lastTraversal: HeapTraversalInput = initialState
    for (heapGraph in heapGraphSequence) {
      val output = objectGrowthDetector.findGrowingObjects(
        heapGraph = heapGraph,
        previousTraversal = lastTraversal,
      )
      if (output is HeapGrowthTraversal && !output.isGrowing) {
        return output
      }
      lastTraversal = output
    }
    check(lastTraversal is HeapGrowthTraversal) {
      "Final output should be a HeapGrowth, traversalCount ${lastTraversal.traversalCount - 1} " +
        "should be >= 2. Output: $lastTraversal"
    }
    return lastTraversal
  }
}

/**
 * @see RepeatingHeapGraphObjectGrowthDetector.findRepeatedlyGrowingObjects
 */
fun ObjectGrowthDetector.repeatingHeapGraph(): RepeatingHeapGraphObjectGrowthDetector {
  return RepeatingHeapGraphObjectGrowthDetector(this)
}
