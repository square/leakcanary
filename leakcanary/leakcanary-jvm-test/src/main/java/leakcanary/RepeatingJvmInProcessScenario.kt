package leakcanary

import shark.HeapGraphProvider
import shark.ObjectGrowthDetector
import shark.RepeatingScenarioObjectGrowthDetector
import shark.repeatingScenario

/**
 * Creates a [RepeatingScenarioObjectGrowthDetector] suitable for JVM in process tests.
 * Typically called on a [ObjectGrowthDetector] created via
 * [shark.forJvmHeap].
 *
 * Dumps the heap by leveraging Hotspot APIs, running an in process GC right before dumping.
 * Deletes the heap dump file as soon as we're done traversing it.
 *
 * @see [RepeatingScenarioObjectGrowthDetector.findRepeatedlyGrowingObjects]
 */
fun ObjectGrowthDetector.repeatingJvmInProcessScenario(
  maxHeapDumps: Int = RepeatingScenarioObjectGrowthDetector.DEFAULT_MAX_HEAP_DUMPS,
  // In process => More than one to account for the impact of running the analysis.
  scenarioLoopsPerDump: Int = 2,
): RepeatingScenarioObjectGrowthDetector {
  return repeatingScenario(
    heapGraphProvider = HeapGraphProvider.dumpingAndDeleting(
      heapDumper = HeapDumper.forJvmInProcess()
        .withGc(gcTrigger = GcTrigger.inProcess()),
      heapDumpFileProvider = HeapDumpFileProvider.tempFile()
    ),
    maxHeapDumps = maxHeapDumps,
    scenarioLoopsPerDump = scenarioLoopsPerDump,
  )
}
