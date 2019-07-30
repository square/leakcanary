[shark](../../index.md) / [shark](../index.md) / [LibraryLeakReferenceMatcher](index.md) / [patternApplies](./pattern-applies.md)

# patternApplies

`val patternApplies: (HeapGraph) -> `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Whether the identified leak may exist in the provided [HeapGraph](#). Defaults to true. If
the heap dump comes from a VM that runs a different version of the library that doesn't
have the leak, then this should return false.

