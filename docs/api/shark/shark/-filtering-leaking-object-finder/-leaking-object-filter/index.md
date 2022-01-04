//[shark](../../../../index.md)/[shark](../../index.md)/[FilteringLeakingObjectFinder](../index.md)/[LeakingObjectFilter](index.md)

# LeakingObjectFilter

[jvm]\
fun interface [LeakingObjectFilter](index.md)

Filter to be passed to the [FilteringLeakingObjectFinder](../index.md) constructor.

## Functions

| Name | Summary |
|---|---|
| [isLeakingObject](is-leaking-object.md) | [jvm]<br>abstract fun [isLeakingObject](is-leaking-object.md)(heapObject: HeapObject): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns whether the passed in [heapObject](is-leaking-object.md) is leaking. This should only return true when we're 100% sure the passed in [heapObject](is-leaking-object.md) should not be in memory anymore. |
