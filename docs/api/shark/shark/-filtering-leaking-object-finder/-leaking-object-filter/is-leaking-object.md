//[shark](../../../../index.md)/[shark](../../index.md)/[FilteringLeakingObjectFinder](../index.md)/[LeakingObjectFilter](index.md)/[isLeakingObject](is-leaking-object.md)

# isLeakingObject

[jvm]\
abstract fun [isLeakingObject](is-leaking-object.md)(heapObject: HeapObject): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Returns whether the passed in [heapObject](is-leaking-object.md) is leaking. This should only return true when we're 100% sure the passed in [heapObject](is-leaking-object.md) should not be in memory anymore.
