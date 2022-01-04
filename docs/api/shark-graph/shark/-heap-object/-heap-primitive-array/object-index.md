//[shark-graph](../../../../index.md)/[shark](../../index.md)/[HeapObject](../index.md)/[HeapPrimitiveArray](index.md)/[objectIndex](object-index.md)

# objectIndex

[jvm]\
open override val [objectIndex](object-index.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)

An positive object index that's specific to how Shark stores objects in memory. The index starts at 0 and ends at [HeapGraph.objectCount](../../-heap-graph/object-count.md) - 1. There are no gaps, every index value corresponds to an object. Classes are first, then instances, then object arrays then primitive arrays.
