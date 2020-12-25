[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [BackgroundTrigger](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`BackgroundTrigger(application: Application, analysisClient: `[`HeapAnalysisClient`](../-heap-analysis-client/index.md)`, analysisExecutor: `[`Executor`](https://docs.oracle.com/javase/6/docs/api/java/util/concurrent/Executor.html)`, processInfo: `[`ProcessInfo`](../-process-info/index.md)` = ProcessInfo.Real, analysisCallback: (`[`HeapAnalysisJob.Result`](../-heap-analysis-job/-result/index.md)`) -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)` = { result ->
    SharkLog.d { "$result" }
  })`