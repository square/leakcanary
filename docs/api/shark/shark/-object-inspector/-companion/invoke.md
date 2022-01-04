//[shark](../../../../index.md)/[shark](../../index.md)/[ObjectInspector](../index.md)/[Companion](index.md)/[invoke](invoke.md)

# invoke

[jvm]\
inline operator fun [invoke](invoke.md)(crossinline block: ([ObjectReporter](../../-object-reporter/index.md)) -&gt; [Unit](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)): [ObjectInspector](../index.md)

Utility function to create a [ObjectInspector](../index.md) from the passed in [block](invoke.md) lambda instead of using the anonymous object : OnHeapAnalyzedListener syntax.

Usage:

val inspector = ObjectInspector { reporter -&gt;\
\
}
