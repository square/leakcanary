package shark

class HeapDumpingObjectGrowthDetector(
  private val maxHeapDumps: Int,
  private val heapGraphProvider: HeapGraphProvider,
  private val scenarioLoopsPerDump: Int,
  private val detector: RepeatedObjectGrowthDetector
) : LiveObjectGrowthDetector {

  init {
    check(maxHeapDumps >= 2) {
      "There should be at least 2 heap dumps"
    }
    check(scenarioLoopsPerDump >= 1) {
      "There should be at least 1 scenario loop per dump"
    }
  }

  override fun findRepeatedlyGrowingObjects(roundTripScenario: () -> Unit): List<ShortestPathObjectNode> {
    val heapDumpSequence = dumpHeapOnNext(roundTripScenario)
    return detector.findRepeatedlyGrowingObjects(heapDumpSequence, scenarioLoopsPerDump)
  }

  private fun dumpHeapOnNext(
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
}
