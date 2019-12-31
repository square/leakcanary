[shark](../../index.md) / [shark](../index.md) / [LeakTrace](index.md) / [suspectReferenceSubpath](./suspect-reference-subpath.md)

# suspectReferenceSubpath

`val suspectReferenceSubpath: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`LeakTraceReference`](../-leak-trace-reference/index.md)`>`

A part of [referencePath](reference-path.md) that contains the references suspected to cause the leak.
Starts at the last non leaking object and ends before the first leaking object.

