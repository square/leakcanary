[leakcanary-android-instrumentation](../../index.md) / [leakcanary](../index.md) / [FailTestOnLeakRunListener](index.md) / [onAnalysisPerformed](./on-analysis-performed.md)

# onAnalysisPerformed

`protected open fun onAnalysisPerformed(heapAnalysis: HeapAnalysis): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Called when a heap analysis has been performed and a result is available.

The default implementation call [failTest](fail-test.md) if the [heapAnalysis](on-analysis-performed.md#leakcanary.FailTestOnLeakRunListener$onAnalysisPerformed(shark.HeapAnalysis)/heapAnalysis) failed or if
[HeapAnalysisSuccess.applicationLeaks](#) is not empty.

