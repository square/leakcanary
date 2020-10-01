[shark](../../index.md) / [shark](../index.md) / [LibraryLeak](./index.md)

# LibraryLeak

`data class LibraryLeak : `[`Leak`](../-leak/index.md)

A leak found by [HeapAnalyzer](../-heap-analyzer/index.md), where the only path to the leaking object required going
through a reference matched by [pattern](pattern.md), as provided to a [LibraryLeakReferenceMatcher](../-library-leak-reference-matcher/index.md)
instance. This is a known leak in library code that is beyond your control.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `LibraryLeak(leakTraces: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`LeakTrace`](../-leak-trace/index.md)`>, pattern: `[`ReferencePattern`](../-reference-pattern/index.md)`, description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`)`<br>A leak found by [HeapAnalyzer](../-heap-analyzer/index.md), where the only path to the leaking object required going through a reference matched by [pattern](pattern.md), as provided to a [LibraryLeakReferenceMatcher](../-library-leak-reference-matcher/index.md) instance. This is a known leak in library code that is beyond your control. |

### Properties

| Name | Summary |
|---|---|
| [description](description.md) | `val description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>A description that conveys what we know about this library leak. |
| [leakTraces](leak-traces.md) | `val leakTraces: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`LeakTrace`](../-leak-trace/index.md)`>`<br>Group of leak traces which share the same leak signature. |
| [pattern](pattern.md) | `val pattern: `[`ReferencePattern`](../-reference-pattern/index.md)<br>The pattern that matched one of the references in each of [leakTraces](leak-traces.md), as provided to a [LibraryLeakReferenceMatcher](../-library-leak-reference-matcher/index.md) instance. |
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
