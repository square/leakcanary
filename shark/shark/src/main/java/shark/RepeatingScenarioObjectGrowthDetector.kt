package shark

/**
 * @see [findRepeatedlyGrowingObjects]
 */
interface RepeatingScenarioObjectGrowthDetector {

  /**
   * Detects object growth by iterating through [roundTripScenario] repeatedly and dumping the heap
   * every [scenarioLoopsPerDump] until no object growth is detected or we reach [maxHeapDumps].
   * Returns the [HeapDiff] for the last iteration. You can check
   * [HeapDiff.isGrowing] and [HeapDiff.growingObjects] to report object growth.
   *
   * @param scenarioLoopsPerDump How many times a scenario that might cause heap growth is repeated
   * in between each dump and traversal. This leads the traversal algorithm to only look at objects
   * that are growing at least [scenarioLoopsPerDump] times since the previous traversal. While
   * 1 can work fine, we recommend at least 2 to ignore any side effect of dumping the heap.
   */
  fun findRepeatedlyGrowingObjects(
    maxHeapDumps: Int = DEFAULT_MAX_HEAP_DUMPS,
    scenarioLoopsPerDump: Int = DEFAULT_SCENARIO_LOOPS_PER_DUMP,
    roundTripScenario: () -> Unit
  ): HeapDiff

  companion object {
    const val DEFAULT_MAX_HEAP_DUMPS = 5
    const val DEFAULT_SCENARIO_LOOPS_PER_DUMP = 2
  }
}
