package leakcanary

import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog

/**
 * Thrown when using the [NoLeakAssertionFailedError.throwOnApplicationLeaks] HeapAnalysisReporter
 */
class NoLeakAssertionFailedError(
  val heapAnalysis: HeapAnalysisSuccess
) : AssertionError(
  "Application memory leaks were detected:\n$heapAnalysis"
) {
  companion object {
    /**
     * A [HeapAnalysisReporter] that throws a [NoLeakAssertionFailedError] when the heap analysis
     * has application leaks.
     */
    fun throwOnApplicationLeaks(): HeapAnalysisReporter = HeapAnalysisReporter { heapAnalysis ->
      when (heapAnalysis) {
        is HeapAnalysisSuccess -> {
          when {
            heapAnalysis.applicationLeaks.isNotEmpty() -> {
              throw NoLeakAssertionFailedError(heapAnalysis)
            }
            heapAnalysis.libraryLeaks.isNotEmpty() -> {
              SharkLog.d {
                "Test can keep going: heap analysis found 0 application leaks and ${heapAnalysis.libraryLeaks.size} library leaks:\n$heapAnalysis"
              }
            }
            heapAnalysis.unreachableObjects.isNotEmpty() -> {
              SharkLog.d {
                "Test can keep going: heap analysis found 0 leaks and ${heapAnalysis.unreachableObjects.size} watched weakly reachable objects:\n" +
                  heapAnalysis
              }
            }
            else -> {
              SharkLog.d { "Test can keep going: heap analysis found 0 leaks." }
            }
          }
        }
        is HeapAnalysisFailure -> {
          throw heapAnalysis.exception
        }
      }
    }
  }
}
