package leakcanary

import shark.HeapAnalysis

/**
 * Reports the results of a heap analysis created by [AndroidDetectLeaksAssert].
 */
fun interface HeapAnalysisReporter {
  fun reportHeapAnalysis(heapAnalysis: HeapAnalysis)
}
