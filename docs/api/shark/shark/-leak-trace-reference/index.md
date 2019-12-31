[shark](../../index.md) / [shark](../index.md) / [LeakTraceReference](./index.md)

# LeakTraceReference

`data class LeakTraceReference : `[`Serializable`](https://docs.oracle.com/javase/6/docs/api/java/io/Serializable.html)

A [LeakTraceReference](./index.md) represents and origin [LeakTraceObject](../-leak-trace-object/index.md) and either a reference from that
object to the [LeakTraceObject](../-leak-trace-object/index.md) in the next [LeakTraceReference](./index.md) in [LeakTrace.referencePath](../-leak-trace/reference-path.md),
or to [LeakTrace.leakingObject](../-leak-trace/leaking-object.md) if this is the last [LeakTraceReference](./index.md) in
[LeakTrace.referencePath](../-leak-trace/reference-path.md).

### Types

| Name | Summary |
|---|---|
| [ReferenceType](-reference-type/index.md) | `enum class ReferenceType` |

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `LeakTraceReference(originObject: `[`LeakTraceObject`](../-leak-trace-object/index.md)`, referenceType: `[`LeakTraceReference.ReferenceType`](-reference-type/index.md)`, referenceName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`)`<br>A [LeakTraceReference](./index.md) represents and origin [LeakTraceObject](../-leak-trace-object/index.md) and either a reference from that object to the [LeakTraceObject](../-leak-trace-object/index.md) in the next [LeakTraceReference](./index.md) in [LeakTrace.referencePath](../-leak-trace/reference-path.md), or to [LeakTrace.leakingObject](../-leak-trace/leaking-object.md) if this is the last [LeakTraceReference](./index.md) in [LeakTrace.referencePath](../-leak-trace/reference-path.md). |

### Properties

| Name | Summary |
|---|---|
| [originObject](origin-object.md) | `val originObject: `[`LeakTraceObject`](../-leak-trace-object/index.md) |
| [referenceDisplayName](reference-display-name.md) | `val referenceDisplayName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [referenceGenericName](reference-generic-name.md) | `val referenceGenericName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [referenceName](reference-name.md) | `val referenceName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [referenceType](reference-type.md) | `val referenceType: `[`LeakTraceReference.ReferenceType`](-reference-type/index.md) |
