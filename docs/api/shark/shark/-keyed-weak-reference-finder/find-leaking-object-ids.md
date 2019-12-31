[shark](../../index.md) / [shark](../index.md) / [KeyedWeakReferenceFinder](index.md) / [findLeakingObjectIds](./find-leaking-object-ids.md)

# findLeakingObjectIds

`fun findLeakingObjectIds(graph: HeapGraph): `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`>`

Overrides [LeakingObjectFinder.findLeakingObjectIds](../-leaking-object-finder/find-leaking-object-ids.md)

For a given heap graph, returns a set of object ids for the objects that are leaking.

