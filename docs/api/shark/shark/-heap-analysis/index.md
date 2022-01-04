//[shark](../../../index.md)/[shark](../index.md)/[HeapAnalysis](index.md)

# HeapAnalysis

[jvm]\
sealed class [HeapAnalysis](index.md) : [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)

The result of an analysis performed by [HeapAnalyzer](../-heap-analyzer/index.md), either a [HeapAnalysisSuccess](../-heap-analysis-success/index.md) or a [HeapAnalysisFailure](../-heap-analysis-failure/index.md). This class is serializable however there are no guarantees of forward compatibility.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Properties

| Name | Summary |
|---|---|
| [analysisDurationMillis](analysis-duration-millis.md) | [jvm]<br>abstract val [analysisDurationMillis](analysis-duration-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Total time spent analyzing the heap. |
| [createdAtTimeMillis](created-at-time-millis.md) | [jvm]<br>abstract val [createdAtTimeMillis](created-at-time-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The [System.currentTimeMillis](https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#currentTimeMillis--) when this [HeapAnalysis](index.md) instance was created. |
| [dumpDurationMillis](dump-duration-millis.md) | [jvm]<br>abstract val [dumpDurationMillis](dump-duration-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Total time spent dumping the heap. |
| [heapDumpFile](heap-dump-file.md) | [jvm]<br>abstract val [heapDumpFile](heap-dump-file.md): [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html)<br>The hprof file that was analyzed. |

## Inheritors

| Name |
|---|
| [HeapAnalysisFailure](../-heap-analysis-failure/index.md) |
| [HeapAnalysisSuccess](../-heap-analysis-success/index.md) |
