package leakcanary

sealed class HeapAnalysisDecision {
  object AnalyzeHeap : HeapAnalysisDecision()
  class NoHeapAnalysis(val reason: String) : HeapAnalysisDecision()
}
