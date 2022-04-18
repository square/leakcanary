package leakcanary

/**
 * Decides whether to dump & analyze the heap to look for leaks in instrumentation tests.
 * The implementation might block for a while to allow temporary leaks to be flushed out, as those
 * aren't that interesting to report and heap analysis increases test duration significantly.
 */
fun interface DetectLeaksInterceptor {
  fun waitUntilReadyForHeapAnalysis(): HeapAnalysisDecision
}
