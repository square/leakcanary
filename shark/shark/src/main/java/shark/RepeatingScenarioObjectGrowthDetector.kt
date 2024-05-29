package shark

/**
 * @see [findRepeatedlyGrowingObjects]
 */
class RepeatingScenarioObjectGrowthDetector(
  private val heapGraphProvider: HeapGraphProvider,
  private val repeatingHeapGraphDetector: RepeatingHeapGraphObjectGrowthDetector,
) {

  /**
   * Detects object growth by iterating through [roundTripScenario] repeatedly and dumping the heap
   * every `scenarioLoopsPerDump` until no object growth is detected or we reach `maxHeapDumps`.
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
    scenarioLoopsPerDump: Int = 2,
    roundTripScenario: () -> Unit
  ): HeapDiff {
    val heapGraphSequence = dumpHeapOnNext(maxHeapDumps, scenarioLoopsPerDump, roundTripScenario)
    return repeatingHeapGraphDetector.findRepeatedlyGrowingObjects(
      scenarioLoopsPerGraph = scenarioLoopsPerDump,
      heapGraphSequence = heapGraphSequence,
    )
  }

  private fun dumpHeapOnNext(
    maxHeapDumps: Int,
    scenarioLoopsPerDump: Int,
    repeatedScenario: () -> Unit,
  ): Sequence<CloseableHeapGraph> {
    val heapDumps = (1..maxHeapDumps).asSequence().map {
      repeat(scenarioLoopsPerDump) {
        repeatedScenario()
      }
      heapGraphProvider.openHeapGraph()
    }
    return heapDumps
  }

  companion object {
    const val DEFAULT_MAX_HEAP_DUMPS = 5
    const val DEFAULT_SCENARIO_LOOPS_PER_DUMP = InitialState.DEFAULT_SCENARIO_LOOPS_PER_GRAPH

    /**
     * In process => More than one to account for the impact of running the analysis.
     */
    const val IN_PROCESS_SCENARIO_LOOPS_PER_DUMP = 2
  }
}
