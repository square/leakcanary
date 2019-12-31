[shark](../../index.md) / [shark](../index.md) / [LeakTraceReference](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`LeakTraceReference(originObject: `[`LeakTraceObject`](../-leak-trace-object/index.md)`, referenceType: `[`LeakTraceReference.ReferenceType`](-reference-type/index.md)`, referenceName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`)`

A [LeakTraceReference](index.md) represents and origin [LeakTraceObject](../-leak-trace-object/index.md) and either a reference from that
object to the [LeakTraceObject](../-leak-trace-object/index.md) in the next [LeakTraceReference](index.md) in [LeakTrace.referencePath](../-leak-trace/reference-path.md),
or to [LeakTrace.leakingObject](../-leak-trace/leaking-object.md) if this is the last [LeakTraceReference](index.md) in
[LeakTrace.referencePath](../-leak-trace/reference-path.md).

