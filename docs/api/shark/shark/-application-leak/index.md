//[shark](../../../index.md)/[shark](../index.md)/[ApplicationLeak](index.md)

# ApplicationLeak

[jvm]\
data class [ApplicationLeak](index.md)(leakTraces: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[LeakTrace](../-leak-trace/index.md)&gt;) : [Leak](../-leak/index.md)

A leak found by [HeapAnalyzer](../-heap-analyzer/index.md) in your application.

## Constructors

| | |
|---|---|
| [ApplicationLeak](-application-leak.md) | [jvm]<br>fun [ApplicationLeak](-application-leak.md)(leakTraces: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[LeakTrace](../-leak-trace/index.md)&gt;) |

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
| [leakTraces](leak-traces.md) | [jvm]<br>open override val [leakTraces](leak-traces.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[LeakTrace](../-leak-trace/index.md)&gt;<br>Group of leak traces which share the same leak signature. |
| [shortDescription](short-description.md) | [jvm]<br>open override val [shortDescription](short-description.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [signature](signature.md) | [jvm]<br>open override val [signature](signature.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>A unique SHA1 hash that represents this group of leak traces. |
| [totalRetainedHeapByteSize](../-leak/total-retained-heap-byte-size.md) | [jvm]<br>val [totalRetainedHeapByteSize](../-leak/total-retained-heap-byte-size.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)?<br>Sum of [LeakTrace.retainedHeapByteSize](../-leak-trace/retained-heap-byte-size.md) for all elements in [leakTraces](../-leak/leak-traces.md). Null if the retained heap size was not computed. |
| [totalRetainedObjectCount](../-leak/total-retained-object-count.md) | [jvm]<br>val [totalRetainedObjectCount](../-leak/total-retained-object-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)?<br>Sum of [LeakTrace.retainedObjectCount](../-leak-trace/retained-object-count.md) for all elements in [leakTraces](../-leak/leak-traces.md). Null if the retained heap size was not computed. |
