package leakcanary

import shark.HeapDiff
import shark.ObjectGrowthDetector
import shark.RepeatingHeapGraphObjectGrowthDetector
import shark.RepeatingScenarioObjectGrowthDetector

/**
 * Creates a [RepeatingScenarioObjectGrowthDetector] suitable for junit based automated tests that
 * can dump the heap.
 *
 * @see [RepeatingScenarioObjectGrowthDetector.findRepeatedlyGrowingObjects]
 */
fun HeapDiff.Companion.repeatingDumpingTestScenario(
  objectGrowthDetector: ObjectGrowthDetector,
  heapDumpDirectoryProvider: HeapDumpDirectoryProvider,
  heapDumper: HeapDumper,
  heapDumpDeletionStrategy: HeapDumpDeletionStrategy,
): RepeatingScenarioObjectGrowthDetector {
  return RepeatingScenarioObjectGrowthDetector(
    heapGraphProvider = DumpingHeapGraphProvider(
      heapDumpFileProvider = DatetimeFormattedHeapDumpFileProvider(
        heapDumpDirectoryProvider = heapDumpDirectoryProvider,
        suffixProvider = {
              TestNameProvider.currentTestName()?.run {
                // JVM test method names can have spaces.
                val escapedMethodName = methodName.replace(' ', '-')
                "_${classSimpleName}-${escapedMethodName}"
              } ?: ""
            }
      ),
      heapDumper = heapDumper,
      heapDumpClosedListener = heapDumpDeletionStrategy
    ),
    repeatingHeapGraphDetector = RepeatingHeapGraphObjectGrowthDetector(
      objectGrowthDetector = objectGrowthDetector,
      completionListener = heapDumpDeletionStrategy
    ),
  )
}

