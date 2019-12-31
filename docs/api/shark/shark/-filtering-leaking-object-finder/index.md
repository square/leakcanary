[shark](../../index.md) / [shark](../index.md) / [FilteringLeakingObjectFinder](./index.md)

# FilteringLeakingObjectFinder

`class FilteringLeakingObjectFinder : `[`LeakingObjectFinder`](../-leaking-object-finder/index.md)

Finds the objects that are leaking by scanning all objects in the heap dump
and delegating the decision to a list of [FilteringLeakingObjectFinder.LeakingObjectFilter](-leaking-object-filter/index.md)

### Types

| Name | Summary |
|---|---|
| [LeakingObjectFilter](-leaking-object-filter/index.md) | `interface LeakingObjectFilter`<br>Filter to be passed to the [FilteringLeakingObjectFinder](./index.md) constructor. |

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `FilteringLeakingObjectFinder(filters: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`FilteringLeakingObjectFinder.LeakingObjectFilter`](-leaking-object-filter/index.md)`>)`<br>Finds the objects that are leaking by scanning all objects in the heap dump and delegating the decision to a list of [FilteringLeakingObjectFinder.LeakingObjectFilter](-leaking-object-filter/index.md) |

### Functions

| Name | Summary |
|---|---|
| [findLeakingObjectIds](find-leaking-object-ids.md) | `fun findLeakingObjectIds(graph: HeapGraph): `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`>`<br>For a given heap graph, returns a set of object ids for the objects that are leaking. |
