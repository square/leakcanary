[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [ObjectWatcher](./index.md)

# ObjectWatcher

`class ObjectWatcher`

[ObjectWatcher](./index.md) can be passed objects to [watch](watch.md). It will create [KeyedWeakReference](../-keyed-weak-reference/index.md) instances
that reference watches objects, and check if those references have been cleared as expected on
the [checkRetainedExecutor](#) executor. If not, these objects are considered retained and
[ObjectWatcher](./index.md) will then notify the [onObjectRetainedListener](#) on that executor thread.

[checkRetainedExecutor](#) is expected to run its tasks on a background thread, with a significant
to give the GC the opportunity to identify weakly reachable objects.

[ObjectWatcher](./index.md) is thread safe.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `ObjectWatcher(clock: `[`Clock`](../-clock/index.md)`, checkRetainedExecutor: `[`Executor`](https://docs.oracle.com/javase/6/docs/api/java/util/concurrent/Executor.html)`, isEnabled: () -> `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = { true })`<br>[ObjectWatcher](./index.md) can be passed objects to [watch](watch.md). It will create [KeyedWeakReference](../-keyed-weak-reference/index.md) instances that reference watches objects, and check if those references have been cleared as expected on the [checkRetainedExecutor](#) executor. If not, these objects are considered retained and [ObjectWatcher](./index.md) will then notify the [onObjectRetainedListener](#) on that executor thread. |

### Properties

| Name | Summary |
|---|---|
| [hasRetainedObjects](has-retained-objects.md) | `val hasRetainedObjects: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if there are watched objects that aren't weakly reachable, and have been watched for long enough to be considered retained. |
| [hasWatchedObjects](has-watched-objects.md) | `val hasWatchedObjects: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if there are watched objects that aren't weakly reachable, even if they haven't been watched for long enough to be considered retained. |
| [retainedObjectCount](retained-object-count.md) | `val retainedObjectCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>Returns the number of retained objects, ie the number of watched objects that aren't weakly reachable, and have been watched for long enough to be considered retained. |
| [retainedObjects](retained-objects.md) | `val retainedObjects: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`>`<br>Returns the objects that are currently considered retained. Useful for logging purposes. Be careful with those objects and release them ASAP as you may creating longer lived leaks then the one that are already there. |

### Functions

| Name | Summary |
|---|---|
| [addOnObjectRetainedListener](add-on-object-retained-listener.md) | `fun addOnObjectRetainedListener(listener: `[`OnObjectRetainedListener`](../-on-object-retained-listener/index.md)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [clearObjectsWatchedBefore](clear-objects-watched-before.md) | `fun clearObjectsWatchedBefore(heapDumpUptimeMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Clears all [KeyedWeakReference](../-keyed-weak-reference/index.md) that were created before [heapDumpUptimeMillis](clear-objects-watched-before.md#leakcanary.ObjectWatcher$clearObjectsWatchedBefore(kotlin.Long)/heapDumpUptimeMillis) (based on [clock](../-clock/uptime-millis.md)) |
| [clearWatchedObjects](clear-watched-objects.md) | `fun clearWatchedObjects(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Clears all [KeyedWeakReference](../-keyed-weak-reference/index.md) |
| [removeOnObjectRetainedListener](remove-on-object-retained-listener.md) | `fun removeOnObjectRetainedListener(listener: `[`OnObjectRetainedListener`](../-on-object-retained-listener/index.md)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [watch](watch.md) | `fun ~~watch~~(watchedObject: `[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Identical to [watch](watch.md) with an empty string reference name.`fun watch(watchedObject: `[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`, description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Watches the provided [watchedObject](watch.md#leakcanary.ObjectWatcher$watch(kotlin.Any, kotlin.String)/watchedObject). |
