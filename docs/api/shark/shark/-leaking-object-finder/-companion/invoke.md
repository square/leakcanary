//[shark](../../../../index.md)/[shark](../../index.md)/[LeakingObjectFinder](../index.md)/[Companion](index.md)/[invoke](invoke.md)

# invoke

[jvm]\
inline operator fun [invoke](invoke.md)(crossinline block: (HeapGraph) -&gt; [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)&gt;): [LeakingObjectFinder](../index.md)

Utility function to create a [LeakingObjectFinder](../index.md) from the passed in [block](invoke.md) lambda instead of using the anonymous object : LeakingObjectFinder syntax.

Usage:

val listener = LeakingObjectFinder {\
\
}
