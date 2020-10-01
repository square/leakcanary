[shark](../../index.md) / [shark](../index.md) / [ApplicationLeak](./index.md)

# ApplicationLeak

`data class ApplicationLeak : `[`Leak`](../-leak/index.md)

A leak found by [HeapAnalyzer](../-heap-analyzer/index.md) in your application.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `ApplicationLeak(leakTraces: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`LeakTrace`](../-leak-trace/index.md)`>)`<br>A leak found by [HeapAnalyzer](../-heap-analyzer/index.md) in your application. |

### Properties

| Name | Summary |
|---|---|
| [leakTraces](leak-traces.md) | `val leakTraces: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`LeakTrace`](../-leak-trace/index.md)`>`<br>Group of leak traces which share the same leak signature. |
| [shortDescription](short-description.md) | `val shortDescription: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [signature](signature.md) | `val signature: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>A unique SHA1 hash that represents this group of leak traces. |

### Inherited Properties

| Name | Summary |
|---|---|
| [totalRetainedHeapByteSize](../-leak/total-retained-heap-byte-size.md) | `val totalRetainedHeapByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`?`<br>Sum of [LeakTrace.retainedHeapByteSize](../-leak-trace/retained-heap-byte-size.md) for all elements in [leakTraces](../-leak/leak-traces.md). Null if the retained heap size was not computed. |
| [totalRetainedObjectCount](../-leak/total-retained-object-count.md) | `val totalRetainedObjectCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`?`<br>Sum of [LeakTrace.retainedObjectCount](../-leak-trace/retained-object-count.md) for all elements in [leakTraces](../-leak/leak-traces.md). Null if the retained heap size was not computed. |

### Functions

| Name | Summary |
|---|---|
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
