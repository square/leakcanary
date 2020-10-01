[shark](../../index.md) / [shark](../index.md) / [HeapAnalysisFailure](./index.md)

# HeapAnalysisFailure

`data class HeapAnalysisFailure : `[`HeapAnalysis`](../-heap-analysis/index.md)

The analysis performed by [HeapAnalyzer](../-heap-analyzer/index.md) did not complete successfully.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `HeapAnalysisFailure(heapDumpFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, createdAtTimeMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, dumpDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = DUMP_DURATION_UNKNOWN, analysisDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, exception: `[`HeapAnalysisException`](../-heap-analysis-exception/index.md)`)`<br>The analysis performed by [HeapAnalyzer](../-heap-analyzer/index.md) did not complete successfully. |

### Properties

| Name | Summary |
|---|---|
| [analysisDurationMillis](analysis-duration-millis.md) | `val analysisDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Total time spent analyzing the heap. |
| [createdAtTimeMillis](created-at-time-millis.md) | `val createdAtTimeMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The [System.currentTimeMillis](https://docs.oracle.com/javase/6/docs/api/java/lang/System.html#currentTimeMillis()) when this [HeapAnalysis](../-heap-analysis/index.md) instance was created. |
| [dumpDurationMillis](dump-duration-millis.md) | `val dumpDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Total time spent dumping the heap. |
| [exception](exception.md) | `val exception: `[`HeapAnalysisException`](../-heap-analysis-exception/index.md)<br>An exception wrapping the actual exception that was thrown. |
| [heapDumpFile](heap-dump-file.md) | `val heapDumpFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)<br>The hprof file that was analyzed. |

### Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
