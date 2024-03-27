package shark

interface LiveObjectGrowthDetector {

  fun findRepeatedlyGrowingObjects(roundTripScenario: () -> Unit): GrowingObjectNodes

  data class Config(
    val maxHeapDumps: Int,
    val scenarioLoopsPerDump: Int,
    val heapGraphProvider: HeapGraphProvider,
  ) {
    fun create(objectRepeatedGrowthDetector: RepeatedObjectGrowthDetector): LiveObjectGrowthDetector {
      return HeapDumpingObjectGrowthDetector(
        maxHeapDumps = maxHeapDumps,
        heapGraphProvider = heapGraphProvider,
        scenarioLoopsPerDump = scenarioLoopsPerDump,
        detector = objectRepeatedGrowthDetector
      )
    }
  }
}
