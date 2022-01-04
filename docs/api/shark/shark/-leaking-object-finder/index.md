//[shark](../../../index.md)/[shark](../index.md)/[LeakingObjectFinder](index.md)

# LeakingObjectFinder

[jvm]\
fun interface [LeakingObjectFinder](index.md)

Finds the objects that are leaking, for which Shark will compute leak traces.

This is a functional interface with which you can create a [LeakingObjectFinder](index.md) from a lambda.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [findLeakingObjectIds](find-leaking-object-ids.md) | [jvm]<br>abstract fun [findLeakingObjectIds](find-leaking-object-ids.md)(graph: HeapGraph): [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)&gt;<br>For a given heap graph, returns a set of object ids for the objects that are leaking. |

## Inheritors

| Name |
|---|
| [FilteringLeakingObjectFinder](../-filtering-leaking-object-finder/index.md) |
| [KeyedWeakReferenceFinder](../-keyed-weak-reference-finder/index.md) |
