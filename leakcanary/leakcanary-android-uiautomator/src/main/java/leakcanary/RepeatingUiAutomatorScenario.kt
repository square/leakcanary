package leakcanary

import androidx.test.platform.app.InstrumentationRegistry
import shark.HeapDiff
import shark.ObjectGrowthDetector
import shark.RepeatingScenarioObjectGrowthDetector
import shark.forAndroidHeap

/**
 * Creates a [RepeatingScenarioObjectGrowthDetector] suitable for Android UI Automator tests.
 *
 * Dumps the heap by leveraging adb, running a GC on API 27+ right before dumping.
 *
 * @see [RepeatingScenarioObjectGrowthDetector.findRepeatedlyGrowingObjects]
 */
fun HeapDiff.Companion.repeatingUiAutomatorScenario(
  objectGrowthDetector: ObjectGrowthDetector = ObjectGrowthDetector.forAndroidHeap(),
  dumpedAppPackageName: String = InstrumentationRegistry.getInstrumentation().targetContext.packageName,
  heapDumpDirectoryProvider: HeapDumpDirectoryProvider = AndroidDeviceTempHeapDumpDirectoryProvider(
    heapDumpDirectoryName = "heap_dumps_object_growth_$dumpedAppPackageName"
  ),
  heapDumper: HeapDumper = HeapDumper.forUiAutomatorAsShell(
    withGc = true,
    dumpedAppPackageName = dumpedAppPackageName
  ),
  heapDumpStorageStrategy: HeapDumpStorageStrategy = HeapDumpStorageStrategy.DeleteOnHeapDumpClose { heapDumpFile ->
    UiAutomatorShellFileDeleter.deleteFileUsingShell(heapDumpFile)
  },
): RepeatingScenarioObjectGrowthDetector {
  return DumpingRepeatingScenarioObjectGrowthDetector(
    objectGrowthDetector = objectGrowthDetector,
    heapDumpFileProvider = TestHeapDumpFileProvider(heapDumpDirectoryProvider),
    heapDumper = heapDumper,
    heapDumpStorageStrategy = heapDumpStorageStrategy,
  )
}
