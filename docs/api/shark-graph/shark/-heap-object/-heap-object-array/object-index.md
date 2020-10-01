[shark-graph](../../../index.md) / [shark](../../index.md) / [HeapObject](../index.md) / [HeapObjectArray](index.md) / [objectIndex](./object-index.md)

# objectIndex

`val objectIndex: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)

Overrides [HeapObject.objectIndex](../object-index.md)

An positive object index that's specific to how Shark stores objects in memory.
The index starts at 0 and ends at [HeapGraph.objectCount](../../-heap-graph/object-count.md) - 1. There are no gaps, every index
value corresponds to an object. Classes are first, then instances, then object arrays then
primitive arrays.

