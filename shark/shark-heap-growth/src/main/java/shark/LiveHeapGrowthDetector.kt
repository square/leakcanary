package shark

import shark.DiffingHeapGrowthDetector.HeapDumpAfterLoopingScenario

class LiveHeapGrowthDetector(
  private val maxHeapDumps: Int,
  private val heapGraphProvider: HeapGraphProvider,
  private val scenarioLoopsPerDump: Int,
  private val detector: LoopingHeapGrowthDetector
) {

  init {
    check(maxHeapDumps >= 2) {
      "There should be at least 2 heap dumps"
    }
    check(scenarioLoopsPerDump >= 1) {
      "There should be at least 1 scenario loop per dump"
    }
  }

  fun detectRepeatedHeapGrowth(repeatedScenario: () -> Unit): HeapTraversalWithDiff {
    val heapDumps = dumpHeapRepeated(repeatedScenario)
    return detector.detectRepeatedHeapGrowth(heapDumps)
  }

  private fun dumpHeapRepeated(
    repeatedScenario: () -> Unit,
  ): Sequence<HeapDumpAfterLoopingScenario> {

    val heapDumps = (1..maxHeapDumps).asSequence().map {
      repeat(scenarioLoopsPerDump) {
        repeatedScenario()
      }
      HeapDumpAfterLoopingScenario(heapGraphProvider.openHeapGraph(), scenarioLoopsPerDump)
    }
    return heapDumps
  }
}
