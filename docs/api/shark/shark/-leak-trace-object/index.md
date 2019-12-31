[shark](../../index.md) / [shark](../index.md) / [LeakTraceObject](./index.md)

# LeakTraceObject

`data class LeakTraceObject : `[`Serializable`](https://docs.oracle.com/javase/6/docs/api/java/io/Serializable.html)

### Types

| Name | Summary |
|---|---|
| [LeakingStatus](-leaking-status/index.md) | `enum class LeakingStatus` |
| [ObjectType](-object-type/index.md) | `enum class ObjectType` |

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `LeakTraceObject(type: `[`LeakTraceObject.ObjectType`](-object-type/index.md)`, className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, labels: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>, leakingStatus: `[`LeakTraceObject.LeakingStatus`](-leaking-status/index.md)`, leakingStatusReason: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`)` |

### Properties

| Name | Summary |
|---|---|
| [className](class-name.md) | `val className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Class name of the object. The class name format is the same as what would be returned by [Class.getName](https://docs.oracle.com/javase/6/docs/api/java/lang/Class.html#getName()). |
| [classSimpleName](class-simple-name.md) | `val classSimpleName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns {@link #className} without the package, ie stripped of any string content before the last period (included). |
| [labels](labels.md) | `val labels: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>`<br>Labels that were computed during analysis. A label provides extra information that helps understand the state of the leak trace object. |
| [leakingStatus](leaking-status.md) | `val leakingStatus: `[`LeakTraceObject.LeakingStatus`](-leaking-status/index.md) |
| [leakingStatusReason](leaking-status-reason.md) | `val leakingStatusReason: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [type](type.md) | `val type: `[`LeakTraceObject.ObjectType`](-object-type/index.md) |
| [typeName](type-name.md) | `val typeName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
