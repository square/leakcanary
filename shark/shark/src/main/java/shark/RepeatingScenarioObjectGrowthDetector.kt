package shark

/**
 * @see [findRepeatedlyGrowingObjects]
 */
class RepeatingScenarioObjectGrowthDetector(
  /**
   * Dumps the heap and opens the heap dump file as a [shark.CloseableHeapGraph]
   */
  private val heapGraphProvider: HeapGraphProvider,
  objectGrowthDetector: ObjectGrowthDetector,
  maxHeapDumps: Int = DEFAULT_MAX_HEAP_DUMPS,
  scenarioLoopsPerDump: Int = DEFAULT_SCENARIO_LOOPS_PER_DUMP,
) {

  private val repeatingHeapGraphDetector = objectGrowthDetector.repeatingHeapGraph()

  private val initialState = InitialState(
    scenarioLoopsPerGraph = scenarioLoopsPerDump,
    heapGraphCount = maxHeapDumps
  )

  /**
   * Detects object growth by iterating through [roundTripScenario] repeatedly and dumping the heap
   * every `scenarioLoopsPerDump` until no object growth is detected or we reach `maxHeapDumps`.
   * Returns the [HeapGrowthTraversal] for the last iteration. You can check
   * [HeapGrowthTraversal.isGrowing] and [HeapGrowthTraversal.growingObjects] to report object growth.
   */
  fun findRepeatedlyGrowingObjects(roundTripScenario: () -> Unit): HeapGrowthTraversal {
    val heapGraphSequence = dumpHeapOnNext(roundTripScenario)
    return repeatingHeapGraphDetector.findRepeatedlyGrowingObjects(
      initialState = initialState,
      heapGraphSequence = heapGraphSequence,
    )
  }

  private fun dumpHeapOnNext(
    repeatedScenario: () -> Unit,
  ): Sequence<CloseableHeapGraph> {
    val heapDumps = (1..initialState.heapGraphCount!!).asSequence().map {
      repeat(initialState.scenarioLoopsPerGraph) {
        repeatedScenario()
      }
      heapGraphProvider.openHeapGraph()
    }
    return heapDumps
  }

  companion object {
    const val DEFAULT_MAX_HEAP_DUMPS = 5
    const val DEFAULT_SCENARIO_LOOPS_PER_DUMP = 1
  }
}

/**
 * @see [RepeatingScenarioObjectGrowthDetector.findRepeatedlyGrowingObjects].
 */
fun ObjectGrowthDetector.repeatingScenario(
  heapGraphProvider: HeapGraphProvider,
  maxHeapDumps: Int = RepeatingScenarioObjectGrowthDetector.DEFAULT_MAX_HEAP_DUMPS,
  scenarioLoopsPerDump: Int = RepeatingScenarioObjectGrowthDetector.DEFAULT_SCENARIO_LOOPS_PER_DUMP,
): RepeatingScenarioObjectGrowthDetector {
  return RepeatingScenarioObjectGrowthDetector(
    heapGraphProvider = heapGraphProvider,
    objectGrowthDetector = this,
    maxHeapDumps = maxHeapDumps,
    scenarioLoopsPerDump = scenarioLoopsPerDump
  )
}
