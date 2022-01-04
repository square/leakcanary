//[shark-graph](../../../index.md)/[shark](../index.md)/[HeapGraph](index.md)/[findObjectByIndex](find-object-by-index.md)

# findObjectByIndex

[jvm]\
abstract fun [findObjectByIndex](find-object-by-index.md)(objectIndex: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [HeapObject](../-heap-object/index.md)

Returns the [HeapObject](../-heap-object/index.md) corresponding to the provided [objectIndex](find-object-by-index.md), and throws [IllegalArgumentException](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-illegal-argument-exception/index.html) if [objectIndex](find-object-by-index.md) is less than 0 or more than [objectCount](object-count.md) - 1.
