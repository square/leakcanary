package leakcanary

import shark.HeapGraphProvider
import shark.ObjectGrowthDetector
import shark.RepeatingScenarioObjectGrowthDetector
import shark.repeatingScenario

/**
 * Creates a [RepeatingScenarioObjectGrowthDetector] suitable for Android in process tests, such
 * as Espresso tests. Typically called on a [ObjectGrowthDetector] created via
 * [shark.forAndroidHeap].
 *
 * Dumps the heap by leveraging Android APIs, running an in process GC right before dumping.
 * Deletes the heap dump file as soon as we're done traversing it.
 *
 * @see [RepeatingScenarioObjectGrowthDetector.findRepeatedlyGrowingObjects]
 */
fun ObjectGrowthDetector.repeatingAndroidInProcessScenario(
  maxHeapDumps: Int = RepeatingScenarioObjectGrowthDetector.DEFAULT_MAX_HEAP_DUMPS,
  scenarioLoopsPerDump: Int = RepeatingScenarioObjectGrowthDetector.IN_PROCESS_SCENARIO_LOOPS_PER_DUMP,
): RepeatingScenarioObjectGrowthDetector {
  return repeatingScenario(
    heapGraphProvider = HeapGraphProvider.dumpingAndDeleting(
      heapDumper = HeapDumper.forAndroidInProcess()
        .withGc(gcTrigger = GcTrigger.inProcess())
        .withDetectorWarmup(this, androidHeap = true),
      heapDumpFileProvider = HeapDumpFileProvider.tempFile()
    ),
    maxHeapDumps = maxHeapDumps,
    scenarioLoopsPerDump = scenarioLoopsPerDump,
  )
}
