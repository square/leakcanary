//[shark](../../../index.md)/[shark](../index.md)/[HeapAnalysisSuccess](index.md)

# HeapAnalysisSuccess

[jvm]\
data class [HeapAnalysisSuccess](index.md)(heapDumpFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), createdAtTimeMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), dumpDurationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), analysisDurationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), metadata: [Map](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-map/index.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt;, applicationLeaks: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[ApplicationLeak](../-application-leak/index.md)&gt;, libraryLeaks: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[LibraryLeak](../-library-leak/index.md)&gt;, unreachableObjects: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[LeakTraceObject](../-leak-trace-object/index.md)&gt;) : [HeapAnalysis](../-heap-analysis/index.md)

The result of a successful heap analysis performed by [HeapAnalyzer](../-heap-analyzer/index.md).

## Constructors

| | |
|---|---|
| [HeapAnalysisSuccess](-heap-analysis-success.md) | [jvm]<br>fun [HeapAnalysisSuccess](-heap-analysis-success.md)(heapDumpFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), createdAtTimeMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), dumpDurationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) = DUMP_DURATION_UNKNOWN, analysisDurationMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), metadata: [Map](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-map/index.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt;, applicationLeaks: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[ApplicationLeak](../-application-leak/index.md)&gt;, libraryLeaks: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[LibraryLeak](../-library-leak/index.md)&gt;, unreachableObjects: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[LeakTraceObject](../-leak-trace-object/index.md)&gt;) |

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
| [allLeaks](all-leaks.md) | [jvm]<br>val [allLeaks](all-leaks.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[Leak](../-leak/index.md)&gt;<br>The list of [Leak](../-leak/index.md) found in the heap dump by [HeapAnalyzer](../-heap-analyzer/index.md), ie all [applicationLeaks](application-leaks.md) and all [libraryLeaks](library-leaks.md) in one list. |
| [analysisDurationMillis](analysis-duration-millis.md) | [jvm]<br>open override val [analysisDurationMillis](analysis-duration-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Total time spent analyzing the heap. |
| [applicationLeaks](application-leaks.md) | [jvm]<br>val [applicationLeaks](application-leaks.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[ApplicationLeak](../-application-leak/index.md)&gt;<br>The list of [ApplicationLeak](../-application-leak/index.md) found in the heap dump by [HeapAnalyzer](../-heap-analyzer/index.md). |
| [createdAtTimeMillis](created-at-time-millis.md) | [jvm]<br>open override val [createdAtTimeMillis](created-at-time-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The [System.currentTimeMillis](https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#currentTimeMillis--) when this [HeapAnalysis](../-heap-analysis/index.md) instance was created. |
| [dumpDurationMillis](dump-duration-millis.md) | [jvm]<br>open override val [dumpDurationMillis](dump-duration-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Total time spent dumping the heap. |
| [heapDumpFile](heap-dump-file.md) | [jvm]<br>open override val [heapDumpFile](heap-dump-file.md): [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html)<br>The hprof file that was analyzed. |
| [libraryLeaks](library-leaks.md) | [jvm]<br>val [libraryLeaks](library-leaks.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[LibraryLeak](../-library-leak/index.md)&gt;<br>The list of [LibraryLeak](../-library-leak/index.md) found in the heap dump by [HeapAnalyzer](../-heap-analyzer/index.md). |
| [metadata](metadata.md) | [jvm]<br>val [metadata](metadata.md): [Map](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-map/index.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt; |
| [unreachableObjects](unreachable-objects.md) | [jvm]<br>val [unreachableObjects](unreachable-objects.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[LeakTraceObject](../-leak-trace-object/index.md)&gt; |
