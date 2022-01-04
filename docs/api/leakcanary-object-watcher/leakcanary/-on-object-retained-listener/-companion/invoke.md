//[leakcanary-object-watcher](../../../../index.md)/[leakcanary](../../index.md)/[OnObjectRetainedListener](../index.md)/[Companion](index.md)/[invoke](invoke.md)

# invoke

[jvm]\
inline operator fun [invoke](invoke.md)(crossinline block: () -&gt; [Unit](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)): [OnObjectRetainedListener](../index.md)

Utility function to create a [OnObjectRetainedListener](../index.md) from the passed in [block](invoke.md) lambda instead of using the anonymous object : OnObjectRetainedListener syntax.

Usage:

val listener = OnObjectRetainedListener {\
\
}
