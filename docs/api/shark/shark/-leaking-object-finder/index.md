[shark](../../index.md) / [shark](../index.md) / [LeakingObjectFinder](./index.md)

# LeakingObjectFinder

`interface LeakingObjectFinder`

Finds the objects that are leaking, for which Shark will compute
leak traces.

You can create a [LeakingObjectFinder](./index.md) from a lambda by calling [invoke](invoke.md).

### Functions

| Name | Summary |
|---|---|
| [findLeakingObjectIds](find-leaking-object-ids.md) | `abstract fun findLeakingObjectIds(graph: HeapGraph): `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`>`<br>For a given heap graph, returns a set of object ids for the objects that are leaking. |

### Companion Object Functions

| Name | Summary |
|---|---|
| [invoke](invoke.md) | `operator fun invoke(block: (HeapGraph) -> `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`>): `[`LeakingObjectFinder`](./index.md)<br>Utility function to create a [LeakingObjectFinder](./index.md) from the passed in [block](invoke.md#shark.LeakingObjectFinder.Companion$invoke(kotlin.Function1((shark.HeapGraph, kotlin.collections.Set((kotlin.Long)))))/block) lambda instead of using the anonymous `object : LeakingObjectFinder` syntax. |

### Inheritors

| Name | Summary |
|---|---|
| [FilteringLeakingObjectFinder](../-filtering-leaking-object-finder/index.md) | `class FilteringLeakingObjectFinder : `[`LeakingObjectFinder`](./index.md)<br>Finds the objects that are leaking by scanning all objects in the heap dump and delegating the decision to a list of [FilteringLeakingObjectFinder.LeakingObjectFilter](../-filtering-leaking-object-finder/-leaking-object-filter/index.md) |
| [KeyedWeakReferenceFinder](../-keyed-weak-reference-finder/index.md) | `object KeyedWeakReferenceFinder : `[`LeakingObjectFinder`](./index.md)<br>Finds all objects tracked by a KeyedWeakReference, ie all objects that were passed to ObjectWatcher.watch. |
