package leakcanary

import shark.HeapDumpingObjectGrowthDetector
import shark.LiveObjectGrowthDetector
import shark.RepeatedObjectGrowthDetector

// TODO This name is wrong, and create method is wrong.
object JvmLiveObjectGrowthDetector {

  fun create(
    maxHeapDumps: Int = 5,
    scenarioLoopsPerDump: Int = 5,
    heapDumpFileProvider: HeapDumpFileProvider = TempHeapDumpFileProvider,
    heapDumper: HeapDumper = HotSpotHeapDumper,
    repeatedObjectGrowthDetector: RepeatedObjectGrowthDetector
  ): LiveObjectGrowthDetector {
    val heapGraphProvider =
      DumpingDeletingOnCloseHeapGraphProvider(heapDumpFileProvider, heapDumper)
    return HeapDumpingObjectGrowthDetector(
      maxHeapDumps = maxHeapDumps,
      heapGraphProvider = heapGraphProvider,
      scenarioLoopsPerDump = scenarioLoopsPerDump,
      detector = repeatedObjectGrowthDetector
    )
  }
}
