package leakcanary

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
  dumpedAppPackageName: String = InstrumentationRegistry.getInstrumentation().targetContext.packageName,
  maxHeapDumps: Int = RepeatingScenarioObjectGrowthDetector.DEFAULT_MAX_HEAP_DUMPS,
  scenarioLoopsPerDump: Int = RepeatingScenarioObjectGrowthDetector.DEFAULT_SCENARIO_LOOPS_PER_DUMP,
): RepeatingScenarioObjectGrowthDetector {
  return repeatingScenario(
    heapGraphProvider = HeapGraphProvider.dumpingAndDeleting(
      heapDumper = HeapDumper.forUiAutomatorAsShell(
        withGc = true,
        dumpedAppPackageName = dumpedAppPackageName
      ),
      heapDumpFileProvider = HeapDumpFileProvider.datetimeFormatted(
        directory = File("/data/local/tmp/"),
        suffix = "-$dumpedAppPackageName"
      ),
      fileDeleter = UiAutomatorShellFileDeleter
    ),
    maxHeapDumps = maxHeapDumps,
    scenarioLoopsPerDump = scenarioLoopsPerDump,
  )
}
