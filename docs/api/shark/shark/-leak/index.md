[shark](../../index.md) / [shark](../index.md) / [Leak](./index.md)

# Leak

`sealed class Leak : `[`Serializable`](https://docs.oracle.com/javase/6/docs/api/java/io/Serializable.html)

A leak found by [HeapAnalyzer](../-heap-analyzer/index.md), either an [ApplicationLeak](../-application-leak/index.md) or a [LibraryLeak](../-library-leak/index.md).

### Properties

| Name | Summary |
|---|---|
| [className](class-name.md) | `abstract val className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Class name of the leaking object. The class name format is the same as what would be returned by [Class.getName](https://docs.oracle.com/javase/6/docs/api/java/lang/Class.html#getName()). |
| [classSimpleName](class-simple-name.md) | `val classSimpleName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns [className](class-name.md) stripped of any string content before the last period (included). |
| [groupHash](group-hash.md) | `val groupHash: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>A unique SHA1 hash that represents this group of leaks. |
| [leakTrace](leak-trace.md) | `abstract val leakTrace: `[`LeakTrace`](../-leak-trace/index.md)<br>Shortest path from GC roots to the leaking object. |
| [retainedHeapByteSize](retained-heap-byte-size.md) | `abstract val retainedHeapByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`?`<br>The number of bytes which would be freed if all references to the leaking object were released. Null if the retained heap size was not computed. |

### Functions

| Name | Summary |
|---|---|
| [createGroupHash](create-group-hash.md) | `abstract fun createGroupHash(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [toString](to-string.md) | `open fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

### Inheritors

| Name | Summary |
|---|---|
| [ApplicationLeak](../-application-leak/index.md) | `data class ApplicationLeak : `[`Leak`](./index.md)<br>A leak found by [HeapAnalyzer](../-heap-analyzer/index.md) in your application. |
| [LibraryLeak](../-library-leak/index.md) | `data class LibraryLeak : `[`Leak`](./index.md)<br>A leak found by [HeapAnalyzer](../-heap-analyzer/index.md), where the only path to the leaking object required going through a reference matched by [pattern](../-library-leak/pattern.md), as provided to a [LibraryLeakReferenceMatcher](../-library-leak-reference-matcher/index.md) instance. This is a known leak in library code that is beyond your control. |
