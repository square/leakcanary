[shark](../../../index.md) / [shark](../../index.md) / [FilteringLeakingObjectFinder](../index.md) / [LeakingObjectFilter](index.md) / [isLeakingObject](./is-leaking-object.md)

# isLeakingObject

`abstract fun isLeakingObject(heapObject: HeapObject): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Returns whether the passed in [heapObject](is-leaking-object.md#shark.FilteringLeakingObjectFinder.LeakingObjectFilter$isLeakingObject(shark.HeapObject)/heapObject) is leaking. This should only return true
when we're 100% sure the passed in [heapObject](is-leaking-object.md#shark.FilteringLeakingObjectFinder.LeakingObjectFilter$isLeakingObject(shark.HeapObject)/heapObject) should not be in memory anymore.

