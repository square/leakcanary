[shark](../../index.md) / [shark](../index.md) / [LeakTraceElement](./index.md)

# LeakTraceElement

`data class LeakTraceElement : `[`Serializable`](https://docs.oracle.com/javase/6/docs/api/java/io/Serializable.html)

### Types

| Name | Summary |
|---|---|
| [Holder](-holder/index.md) | `enum class Holder` |
| [Type](-type/index.md) | `enum class Type` |

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `LeakTraceElement(reference: `[`LeakReference`](../-leak-reference/index.md)`?, holder: `[`LeakTraceElement.Holder`](-holder/index.md)`, className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, labels: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>, leakStatus: `[`LeakNodeStatus`](../-leak-node-status/index.md)`, leakStatusReason: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`)` |

### Properties

| Name | Summary |
|---|---|
| [className](class-name.md) | `val className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [classSimpleName](class-simple-name.md) | `val classSimpleName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns {@link #className} without the package. |
| [holder](holder.md) | `val holder: `[`LeakTraceElement.Holder`](-holder/index.md) |
| [labels](labels.md) | `val labels: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>`<br>Labels that were computed during analysis. A label provides extra information that helps understand the leak trace element. |
| [leakStatus](leak-status.md) | `val leakStatus: `[`LeakNodeStatus`](../-leak-node-status/index.md) |
| [leakStatusReason](leak-status-reason.md) | `val leakStatusReason: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [reference](reference.md) | `val reference: `[`LeakReference`](../-leak-reference/index.md)`?`<br>Information about the reference that points to the next [LeakTraceElement](./index.md) in [LeakTrace.elements](../-leak-trace/elements.md). Null if this is the last element in the leak trace, ie the leaking object. |
