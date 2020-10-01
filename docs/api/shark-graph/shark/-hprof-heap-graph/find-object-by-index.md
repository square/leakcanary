[shark-graph](../../index.md) / [shark](../index.md) / [HprofHeapGraph](index.md) / [findObjectByIndex](./find-object-by-index.md)

# findObjectByIndex

`fun findObjectByIndex(objectIndex: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`HeapObject`](../-heap-object/index.md)

Overrides [HeapGraph.findObjectByIndex](../-heap-graph/find-object-by-index.md)

Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectIndex](../-heap-graph/find-object-by-index.md#shark.HeapGraph$findObjectByIndex(kotlin.Int)/objectIndex), and throws
[IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) if [objectIndex](../-heap-graph/find-object-by-index.md#shark.HeapGraph$findObjectByIndex(kotlin.Int)/objectIndex) is less than 0 or more than [objectCount](../-heap-graph/object-count.md) - 1.

