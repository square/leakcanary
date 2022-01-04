//[shark](../../../index.md)/[shark](../index.md)/[LeakingObjectFinder](index.md)/[findLeakingObjectIds](find-leaking-object-ids.md)

# findLeakingObjectIds

[jvm]\
abstract fun [findLeakingObjectIds](find-leaking-object-ids.md)(graph: HeapGraph): [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)&gt;

For a given heap graph, returns a set of object ids for the objects that are leaking.
