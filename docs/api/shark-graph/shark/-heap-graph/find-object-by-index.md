[shark-graph](../../index.md) / [shark](../index.md) / [HeapGraph](index.md) / [findObjectByIndex](./find-object-by-index.md)

# findObjectByIndex

`abstract fun findObjectByIndex(objectIndex: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`HeapObject`](../-heap-object/index.md)

Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectIndex](find-object-by-index.md#shark.HeapGraph$findObjectByIndex(kotlin.Int)/objectIndex), and throws
[IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) if [objectIndex](find-object-by-index.md#shark.HeapGraph$findObjectByIndex(kotlin.Int)/objectIndex) is less than 0 or more than [objectCount](object-count.md) - 1.

