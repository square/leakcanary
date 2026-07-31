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
   * Only the paths that already exist in the first heap dump can be reported as growing: a path
   * that shows up later is assumed to be state that [roundTripScenario] builds up on its way to a
   * steady state rather than growth. [roundTripScenario] therefore runs [scenarioLoopsPerDump]
   * times before the first heap dump, and a scenario that only creates the structure that then
   * grows on its 2nd run or later needs [roundTripScenario] to include that first run.
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
