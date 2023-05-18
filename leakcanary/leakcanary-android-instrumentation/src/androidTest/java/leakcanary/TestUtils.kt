package leakcanary

import shark.HeapAnalysis
import shark.HeapAnalysisSuccess

object TestUtils {

  fun assertLeak(expectedLeakClass: Class<*>) {
    val heapAnalysis = detectLeaks()
    val applicationLeaks = heapAnalysis.applicationLeaks
    if (applicationLeaks.size != 1) {
      throw AssertionError(
        "Expected exactly one leak in $heapAnalysis"
      )
    }

    val leak = applicationLeaks.first()

    val leakTrace = leak.leakTraces.first()
    val className = leakTrace.leakingObject.className
    if (className != expectedLeakClass.name) {
      throw AssertionError(
        "Expected a leak of $expectedLeakClass, not $className in $heapAnalysis"
      )
    }
  }

  fun detectLeaks(): HeapAnalysisSuccess {
    var heapAnalysisOrNull: HeapAnalysis? = null
    AndroidDetectLeaksAssert { heapAnalysis ->
      heapAnalysisOrNull = heapAnalysis
    }.assertNoLeaks("")
    if (heapAnalysisOrNull == null) {
      throw AssertionError(
        "Expected analysis to be performed but skipped"
      )
    }
    val heapAnalysis = heapAnalysisOrNull

    if (heapAnalysis !is HeapAnalysisSuccess) {
      throw AssertionError(
        "Expected analysis success not $heapAnalysis"
      )
    }
    // Save disk space on emulator
    heapAnalysis.heapDumpFile.delete()
    return heapAnalysis
  }
}
