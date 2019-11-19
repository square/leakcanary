[shark](../../index.md) / [shark](../index.md) / [ApplicationLeak](./index.md)

# ApplicationLeak

`data class ApplicationLeak : `[`Leak`](../-leak/index.md)

A leak found by [HeapAnalyzer](../-heap-analyzer/index.md) in your application.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `ApplicationLeak(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, leakTrace: `[`LeakTrace`](../-leak-trace/index.md)`, retainedHeapByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`?)`<br>A leak found by [HeapAnalyzer](../-heap-analyzer/index.md) in your application. |

### Properties

| Name | Summary |
|---|---|
| [className](class-name.md) | `val className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Class name of the leaking object. The class name format is the same as what would be returned by [Class.getName](https://docs.oracle.com/javase/6/docs/api/java/lang/Class.html#getName()). |
| [leakTrace](leak-trace.md) | `val leakTrace: `[`LeakTrace`](../-leak-trace/index.md)<br>Shortest path from GC roots to the leaking object. |
| [retainedHeapByteSize](retained-heap-byte-size.md) | `val retainedHeapByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`?`<br>The number of bytes which would be freed if all references to the leaking object were released. Null if the retained heap size was not computed. |

### Inherited Properties

| Name | Summary |
|---|---|
| [classSimpleName](../-leak/class-simple-name.md) | `val classSimpleName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns [className](../-leak/class-name.md) stripped of any string content before the last period (included). |
| [groupHash](../-leak/group-hash.md) | `val groupHash: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>A unique SHA1 hash that represents this group of leaks. |

### Functions

| Name | Summary |
|---|---|
| [createGroupHash](create-group-hash.md) | `fun createGroupHash(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
