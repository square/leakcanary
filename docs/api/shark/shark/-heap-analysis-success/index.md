[shark](../../index.md) / [shark](../index.md) / [HeapAnalysisSuccess](./index.md)

# HeapAnalysisSuccess

`data class HeapAnalysisSuccess : `[`HeapAnalysis`](../-heap-analysis/index.md)

The result of a successful heap analysis performed by [HeapAnalyzer](../-heap-analyzer/index.md).

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `HeapAnalysisSuccess(heapDumpFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, createdAtTimeMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, dumpDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = DUMP_DURATION_UNKNOWN, analysisDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, metadata: `[`Map`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-map/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>, applicationLeaks: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`ApplicationLeak`](../-application-leak/index.md)`>, libraryLeaks: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`LibraryLeak`](../-library-leak/index.md)`>)`<br>The result of a successful heap analysis performed by [HeapAnalyzer](../-heap-analyzer/index.md). |

### Properties

| Name | Summary |
|---|---|
| [allLeaks](all-leaks.md) | `val allLeaks: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`Leak`](../-leak/index.md)`>`<br>The list of [Leak](../-leak/index.md) found in the heap dump by [HeapAnalyzer](../-heap-analyzer/index.md), ie all [applicationLeaks](application-leaks.md) and all [libraryLeaks](library-leaks.md) in one list. |
| [analysisDurationMillis](analysis-duration-millis.md) | `val analysisDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Total time spent analyzing the heap. |
| [applicationLeaks](application-leaks.md) | `val applicationLeaks: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`ApplicationLeak`](../-application-leak/index.md)`>`<br>The list of [ApplicationLeak](../-application-leak/index.md) found in the heap dump by [HeapAnalyzer](../-heap-analyzer/index.md). |
| [createdAtTimeMillis](created-at-time-millis.md) | `val createdAtTimeMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The [System.currentTimeMillis](https://docs.oracle.com/javase/6/docs/api/java/lang/System.html#currentTimeMillis()) when this [HeapAnalysis](../-heap-analysis/index.md) instance was created. |
| [dumpDurationMillis](dump-duration-millis.md) | `val dumpDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Total time spent dumping the heap. |
| [heapDumpFile](heap-dump-file.md) | `val heapDumpFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)<br>The hprof file that was analyzed. |
| [libraryLeaks](library-leaks.md) | `val libraryLeaks: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`LibraryLeak`](../-library-leak/index.md)`>`<br>The list of [LibraryLeak](../-library-leak/index.md) found in the heap dump by [HeapAnalyzer](../-heap-analyzer/index.md). |
| [metadata](metadata.md) | `val metadata: `[`Map`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-map/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>` |

### Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

### Companion Object Functions

| Name | Summary |
|---|---|
| [upgradeFrom20Deserialized](upgrade-from20-deserialized.md) | `fun upgradeFrom20Deserialized(fromV20: `[`HeapAnalysisSuccess`](./index.md)`): `[`HeapAnalysisSuccess`](./index.md)<br>If [fromV20](upgrade-from20-deserialized.md#shark.HeapAnalysisSuccess.Companion$upgradeFrom20Deserialized(shark.HeapAnalysisSuccess)/fromV20) was serialized in LeakCanary 2.0, you must deserialize it and call this method to create a usable [HeapAnalysisSuccess](./index.md) instance. |
