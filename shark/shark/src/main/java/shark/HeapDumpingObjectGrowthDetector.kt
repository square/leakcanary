package shark

class HeapDumpingObjectGrowthDetector(
  private val heapGraphProvider: HeapGraphProvider,
  private val detector: HeapGraphSequenceObjectGrowthDetector,
  maxHeapDumps: Int,
  scenarioLoopsPerDump: Int,
) {

  private val initialState = InitialState(
    scenarioLoopsPerGraph = scenarioLoopsPerDump,
    heapGraphCount = maxHeapDumps
  )

  fun findRepeatedlyGrowingObjects(roundTripScenario: () -> Unit): HeapGrowth {
    val heapGraphSequence = dumpHeapOnNext(roundTripScenario)
    return detector.findRepeatedlyGrowingObjects(
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
}

fun ObjectGrowthDetector.fromHeapDumpingRepeatedScenario(
  heapGraphProvider: HeapGraphProvider,
  maxHeapDumps: Int = 5,
  scenarioLoopsPerDump: Int = 1,
): HeapDumpingObjectGrowthDetector {
  return HeapDumpingObjectGrowthDetector(
    heapGraphProvider, fromHeapGraphSequence(), maxHeapDumps, scenarioLoopsPerDump
  )
}
