[shark](../../index.md) / [shark](../index.md) / [Leak](./index.md)

# Leak

`sealed class Leak : `[`Serializable`](https://docs.oracle.com/javase/6/docs/api/java/io/Serializable.html)

A leak found by [HeapAnalyzer](../-heap-analyzer/index.md), either an [ApplicationLeak](../-application-leak/index.md) or a [LibraryLeak](../-library-leak/index.md).

### Properties

| Name | Summary |
|---|---|
| [leakTraces](leak-traces.md) | `abstract val leakTraces: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`LeakTrace`](../-leak-trace/index.md)`>`<br>Group of leak traces which share the same leak signature. |
| [shortDescription](short-description.md) | `abstract val shortDescription: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [signature](signature.md) | `abstract val signature: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>A unique SHA1 hash that represents this group of leak traces. |
| [totalRetainedHeapByteSize](total-retained-heap-byte-size.md) | `val totalRetainedHeapByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`?`<br>Sum of [LeakTrace.retainedHeapByteSize](../-leak-trace/retained-heap-byte-size.md) for all elements in [leakTraces](leak-traces.md). Null if the retained heap size was not computed. |
| [totalRetainedObjectCount](total-retained-object-count.md) | `val totalRetainedObjectCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`?`<br>Sum of [LeakTrace.retainedObjectCount](../-leak-trace/retained-object-count.md) for all elements in [leakTraces](leak-traces.md). Null if the retained heap size was not computed. |

### Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | `open fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

### Inheritors

| Name | Summary |
|---|---|
| [ApplicationLeak](../-application-leak/index.md) | `data class ApplicationLeak : `[`Leak`](./index.md)<br>A leak found by [HeapAnalyzer](../-heap-analyzer/index.md) in your application. |
| [LibraryLeak](../-library-leak/index.md) | `data class LibraryLeak : `[`Leak`](./index.md)<br>A leak found by [HeapAnalyzer](../-heap-analyzer/index.md), where the only path to the leaking object required going through a reference matched by [pattern](../-library-leak/pattern.md), as provided to a [LibraryLeakReferenceMatcher](../-library-leak-reference-matcher/index.md) instance. This is a known leak in library code that is beyond your control. |
