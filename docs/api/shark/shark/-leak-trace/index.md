[shark](../../index.md) / [shark](../index.md) / [LeakTrace](./index.md)

# LeakTrace

`data class LeakTrace : `[`Serializable`](https://docs.oracle.com/javase/6/docs/api/java/io/Serializable.html)

A chain of references that constitute the shortest strong reference path from a GC root to the
leaking object. Fixing the leak usually means breaking one of the references in that chain.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `LeakTrace(elements: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`LeakTraceElement`](../-leak-trace-element/index.md)`>)`<br>A chain of references that constitute the shortest strong reference path from a GC root to the leaking object. Fixing the leak usually means breaking one of the references in that chain. |

### Properties

| Name | Summary |
|---|---|
| [elements](elements.md) | `val elements: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`LeakTraceElement`](../-leak-trace-element/index.md)`>` |
| [leakCauses](leak-causes.md) | `val leakCauses: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`LeakTraceElement`](../-leak-trace-element/index.md)`>` |

### Functions

| Name | Summary |
|---|---|
| [elementMayBeLeakCause](element-may-be-leak-cause.md) | `fun elementMayBeLeakCause(index: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
