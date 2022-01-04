//[shark](../../../index.md)/[shark](../index.md)/[HeapAnalysisFailure](index.md)

# HeapAnalysisFailure

[jvm]\
data class [HeapAnalysisFailure](index.md)(heapDumpFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), createdAtTimeMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), dumpDurationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), analysisDurationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), exception: [HeapAnalysisException](../-heap-analysis-exception/index.md)) : [HeapAnalysis](../-heap-analysis/index.md)

The analysis performed by [HeapAnalyzer](../-heap-analyzer/index.md) did not complete successfully.

## Constructors

| | |
|---|---|
| [HeapAnalysisFailure](-heap-analysis-failure.md) | [jvm]<br>fun [HeapAnalysisFailure](-heap-analysis-failure.md)(heapDumpFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), createdAtTimeMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), dumpDurationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) = DUMP_DURATION_UNKNOWN, analysisDurationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), exception: [HeapAnalysisException](../-heap-analysis-exception/index.md)) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | [jvm]<br>open override fun [toString](to-string.md)(): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

## Properties

| Name | Summary |
|---|---|
| [analysisDurationMillis](analysis-duration-millis.md) | [jvm]<br>open override val [analysisDurationMillis](analysis-duration-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Total time spent analyzing the heap. |
| [createdAtTimeMillis](created-at-time-millis.md) | [jvm]<br>open override val [createdAtTimeMillis](created-at-time-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The [System.currentTimeMillis](https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#currentTimeMillis--) when this [HeapAnalysis](../-heap-analysis/index.md) instance was created. |
| [dumpDurationMillis](dump-duration-millis.md) | [jvm]<br>open override val [dumpDurationMillis](dump-duration-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Total time spent dumping the heap. |
| [exception](exception.md) | [jvm]<br>val [exception](exception.md): [HeapAnalysisException](../-heap-analysis-exception/index.md)<br>An exception wrapping the actual exception that was thrown. |
| [heapDumpFile](heap-dump-file.md) | [jvm]<br>open override val [heapDumpFile](heap-dump-file.md): [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html)<br>The hprof file that was analyzed. |
