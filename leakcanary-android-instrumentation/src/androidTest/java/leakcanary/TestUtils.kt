package leakcanary

import leakcanary.InstrumentationLeakDetector.Result.AnalysisPerformed
import leakcanary.InstrumentationLeakDetector.Result.NoAnalysis
import shark.HeapAnalysisSuccess

object TestUtils {
  fun assertLeak(expectedLeakClass: Class<*>) {
    val leakDetector = InstrumentationLeakDetector()

    val heapAnalysis = when (val result = leakDetector.detectLeaks()) {
      is NoAnalysis -> throw AssertionError("Expected analysis to be performed")
      is AnalysisPerformed -> result.heapAnalysis
    }

    if (heapAnalysis !is HeapAnalysisSuccess) {
      throw AssertionError(
          "Expected analysis success not $heapAnalysis"
      )
    }

    val applicationLeaks = heapAnalysis.applicationLeaks
    if (applicationLeaks.size != 1) {
      throw AssertionError(
          "Expected exactly one leak in $heapAnalysis"
      )
    }

    val leakInstance = applicationLeaks.first()

    if (leakInstance.className != expectedLeakClass.name) {
      throw AssertionError(
          "Expected a leak of $expectedLeakClass, not ${leakInstance.className} in $heapAnalysis"
      )
    }
  }
}