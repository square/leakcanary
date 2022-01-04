//[shark](../../../index.md)/[shark](../index.md)/[LeakTrace](index.md)/[suspectReferenceSubpath](suspect-reference-subpath.md)

# suspectReferenceSubpath

[jvm]\
val [suspectReferenceSubpath](suspect-reference-subpath.md): [Sequence](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)&lt;[LeakTraceReference](../-leak-trace-reference/index.md)&gt;

A part of [referencePath](reference-path.md) that contains the references suspected to cause the leak. Starts at the last non leaking object and ends before the first leaking object.
