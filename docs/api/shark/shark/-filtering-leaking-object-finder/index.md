//[shark](../../../index.md)/[shark](../index.md)/[FilteringLeakingObjectFinder](index.md)

# FilteringLeakingObjectFinder

[jvm]\
class [FilteringLeakingObjectFinder](index.md)(filters: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[FilteringLeakingObjectFinder.LeakingObjectFilter](-leaking-object-filter/index.md)&gt;) : [LeakingObjectFinder](../-leaking-object-finder/index.md)

Finds the objects that are leaking by scanning all objects in the heap dump and delegating the decision to a list of [FilteringLeakingObjectFinder.LeakingObjectFilter](-leaking-object-filter/index.md)

## Constructors

| | |
|---|---|
| [FilteringLeakingObjectFinder](-filtering-leaking-object-finder.md) | [jvm]<br>fun [FilteringLeakingObjectFinder](-filtering-leaking-object-finder.md)(filters: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[FilteringLeakingObjectFinder.LeakingObjectFilter](-leaking-object-filter/index.md)&gt;) |

## Types

| Name | Summary |
|---|---|
| [LeakingObjectFilter](-leaking-object-filter/index.md) | [jvm]<br>fun interface [LeakingObjectFilter](-leaking-object-filter/index.md)<br>Filter to be passed to the [FilteringLeakingObjectFinder](index.md) constructor. |

## Functions

| Name | Summary |
|---|---|
| [findLeakingObjectIds](find-leaking-object-ids.md) | [jvm]<br>open override fun [findLeakingObjectIds](find-leaking-object-ids.md)(graph: HeapGraph): [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)&gt;<br>For a given heap graph, returns a set of object ids for the objects that are leaking. |
