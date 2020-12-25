[leakcanary-android-release](../../../../index.md) / [leakcanary](../../../index.md) / [HeapAnalysisJob](../../index.md) / [Result](../index.md) / [Done](./index.md)

# Done

`data class Done : `[`HeapAnalysisJob.Result`](../index.md)

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `Done(analysis: HeapAnalysis, stripHeapDumpDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`? = null)` |

### Properties

| Name | Summary |
|---|---|
| [analysis](analysis.md) | `val analysis: HeapAnalysis` |
| [stripHeapDumpDurationMillis](strip-heap-dump-duration-millis.md) | `val stripHeapDumpDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`?`<br>The time spent stripping the hprof of any data if [HeapAnalysisConfig.stripHeapDump](../../../-heap-analysis-config/strip-heap-dump.md) is true, null otherwise. |
