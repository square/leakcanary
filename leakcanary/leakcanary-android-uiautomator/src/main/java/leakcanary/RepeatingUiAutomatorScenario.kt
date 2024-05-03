package leakcanary

import shark.RepeatingScenarioObjectGrowthDetector
import shark.HeapGraphProvider
import shark.ObjectGrowthDetector
import shark.repeatingScenario

/**
 * Creates a [RepeatingScenarioObjectGrowthDetector] suitable for Android UI Automator tests.
 * Typically called on a [ObjectGrowthDetector] created via [shark.forAndroidHeap].
 *
 * Dumps the heap by leveraging adb, running a GC on API 27+ right before dumping.
 * Deletes the heap dump file as soon as we're done traversing it.
 *
 * @see [RepeatingScenarioObjectGrowthDetector.findRepeatedlyGrowingObjects]
 */
fun ObjectGrowthDetector.repeatingUiAutomatorScenario(
  maxHeapDumps: Int = RepeatingScenarioObjectGrowthDetector.DEFAULT_MAX_HEAP_DUMPS,
  scenarioLoopsPerDump: Int = RepeatingScenarioObjectGrowthDetector.DEFAULT_SCENARIO_LOOPS_PER_DUMP,
): RepeatingScenarioObjectGrowthDetector {
  return repeatingScenario(
    // TODO Add a way to delete by running "rm", custom lambda in dumpingAndDeleting
    heapGraphProvider = HeapGraphProvider.dumpingAndDeleting(
      heapDumper = HeapDumper.forUiAutomatorAsShell(withGc = true),
      // TODO This probs won't work, let's try and see.
      heapDumpFileProvider = HeapDumpFileProvider.tempFile()
    ),
    maxHeapDumps = maxHeapDumps,
    scenarioLoopsPerDump = scenarioLoopsPerDump,
  )
}
