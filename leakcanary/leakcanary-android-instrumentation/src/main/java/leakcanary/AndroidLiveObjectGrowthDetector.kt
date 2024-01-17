package leakcanary

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import shark.HeapDumpingObjectGrowthDetector
import shark.LiveObjectGrowthDetector
import shark.RepeatedObjectGrowthDetector

// TODO This name is wrong, and create method is wrong.
object AndroidLiveObjectGrowthDetector {

  fun create(
    maxHeapDumps: Int = 5,
    scenarioLoopsPerDump: Int = 5,
    heapDumpFileProvider: HeapDumpFileProvider = HeapDumpFileProvider.dateFormatted(
      directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        "heap-growth-hprof"
      ),
      prefix = "heap-growth-"
    ),
    heapDumper: HeapDumper = AndroidDebugHeapDumper,
    objectRepeatedGrowthDetector: RepeatedObjectGrowthDetector
  ): LiveObjectGrowthDetector {
    val heapGraphProvider =
      DumpingDeletingOnCloseHeapGraphProvider(heapDumpFileProvider, heapDumper)

    return HeapDumpingObjectGrowthDetector(
      maxHeapDumps = maxHeapDumps,
      heapGraphProvider = heapGraphProvider,
      scenarioLoopsPerDump = scenarioLoopsPerDump,
      detector = objectRepeatedGrowthDetector
    )
  }
}
