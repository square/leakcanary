package shark

import shark.DiffingHeapGrowthDetector.HeapDumpAfterLoopingScenario
import shark.HprofHeapGraph.Companion.openHeapGraph

class ScenarioLoopHeapDumper(
  private val maxHeapDumps: Int,
  private val heapGraphProvider: HeapGraphProvider,
  private val scenarioLoopsPerDump: Int = 1,
) {

  // todo name repeat?
  fun sequenceOfHeapDumps(
    loopingScenario: () -> Unit,
  ): Sequence<HeapDumpAfterLoopingScenario> {
    check(maxHeapDumps > 1) {
      "There should be at least 2 heap dumps"
    }
    val heapDumps = (1..maxHeapDumps).asSequence().map {
      repeat(scenarioLoopsPerDump) {
        loopingScenario()
      }
      HeapDumpAfterLoopingScenario(heapGraphProvider.openHeapGraph(), scenarioLoopsPerDump)
    }
    return heapDumps
  }

}
