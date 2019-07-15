package leakcanary

import leakcanary.InstrumentationLeakDetector.Result.AnalysisPerformed
import leakcanary.InstrumentationLeakDetector.Result.NoAnalysis

object TestUtils {
  fun assertLeak(expectedLeakClass: Class<*>) {
    val leakDetector = InstrumentationLeakDetector()
    val result = leakDetector.detectLeaks()

    val heapAnalysis = when (result) {
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