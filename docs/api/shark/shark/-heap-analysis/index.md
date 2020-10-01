[shark](../../index.md) / [shark](../index.md) / [HeapAnalysis](./index.md)

# HeapAnalysis

`sealed class HeapAnalysis : `[`Serializable`](https://docs.oracle.com/javase/6/docs/api/java/io/Serializable.html)

The result of an analysis performed by [HeapAnalyzer](../-heap-analyzer/index.md), either a [HeapAnalysisSuccess](../-heap-analysis-success/index.md) or a
[HeapAnalysisFailure](../-heap-analysis-failure/index.md). This class is serializable however there are no guarantees of forward
compatibility.

### Properties

| Name | Summary |
|---|---|
| [analysisDurationMillis](analysis-duration-millis.md) | `abstract val analysisDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Total time spent analyzing the heap. |
| [createdAtTimeMillis](created-at-time-millis.md) | `abstract val createdAtTimeMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The [System.currentTimeMillis](https://docs.oracle.com/javase/6/docs/api/java/lang/System.html#currentTimeMillis()) when this [HeapAnalysis](./index.md) instance was created. |
| [dumpDurationMillis](dump-duration-millis.md) | `abstract val dumpDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Total time spent dumping the heap. |
| [heapDumpFile](heap-dump-file.md) | `abstract val heapDumpFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)<br>The hprof file that was analyzed. |

### Companion Object Properties

| Name | Summary |
|---|---|
| [DUMP_DURATION_UNKNOWN](-d-u-m-p_-d-u-r-a-t-i-o-n_-u-n-k-n-o-w-n.md) | `const val DUMP_DURATION_UNKNOWN: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |

### Inheritors

| Name | Summary |
|---|---|
| [HeapAnalysisFailure](../-heap-analysis-failure/index.md) | `data class HeapAnalysisFailure : `[`HeapAnalysis`](./index.md)<br>The analysis performed by [HeapAnalyzer](../-heap-analyzer/index.md) did not complete successfully. |
| [HeapAnalysisSuccess](../-heap-analysis-success/index.md) | `data class HeapAnalysisSuccess : `[`HeapAnalysis`](./index.md)<br>The result of a successful heap analysis performed by [HeapAnalyzer](../-heap-analyzer/index.md). |
