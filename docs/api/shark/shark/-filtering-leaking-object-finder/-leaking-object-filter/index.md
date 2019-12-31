[shark](../../../index.md) / [shark](../../index.md) / [FilteringLeakingObjectFinder](../index.md) / [LeakingObjectFilter](./index.md)

# LeakingObjectFilter

`interface LeakingObjectFilter`

Filter to be passed to the [FilteringLeakingObjectFinder](../index.md) constructor.

### Functions

| Name | Summary |
|---|---|
| [isLeakingObject](is-leaking-object.md) | `abstract fun isLeakingObject(heapObject: HeapObject): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns whether the passed in [heapObject](is-leaking-object.md#shark.FilteringLeakingObjectFinder.LeakingObjectFilter$isLeakingObject(shark.HeapObject)/heapObject) is leaking. This should only return true when we're 100% sure the passed in [heapObject](is-leaking-object.md#shark.FilteringLeakingObjectFinder.LeakingObjectFilter$isLeakingObject(shark.HeapObject)/heapObject) should not be in memory anymore. |
