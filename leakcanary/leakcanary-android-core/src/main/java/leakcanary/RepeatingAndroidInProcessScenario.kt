package leakcanary

import shark.RepeatingScenarioObjectGrowthDetector
import shark.HeapGraphProvider
import shark.ObjectGrowthDetector
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
  // In process => More than one to account for the impact of running the analysis.
  scenarioLoopsPerDump: Int = 2,
): RepeatingScenarioObjectGrowthDetector {
  return repeatingScenario(
    heapGraphProvider = HeapGraphProvider.dumpingAndDeleting(
      heapDumper = HeapDumper.forAndroidInProcess()
        .withGc(gcTrigger = GcTrigger.inProcess()),
      heapDumpFileProvider = HeapDumpFileProvider.tempFile()
    ),
    maxHeapDumps = maxHeapDumps,
    scenarioLoopsPerDump = scenarioLoopsPerDump,
  )
}
