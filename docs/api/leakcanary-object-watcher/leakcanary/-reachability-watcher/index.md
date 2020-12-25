[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [ReachabilityWatcher](./index.md)

# ReachabilityWatcher

`interface ReachabilityWatcher`

### Functions

| Name | Summary |
|---|---|
| [expectWeaklyReachable](expect-weakly-reachable.md) | `abstract fun expectWeaklyReachable(watchedObject: `[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`, description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Expects the provided [watchedObject](expect-weakly-reachable.md#leakcanary.ReachabilityWatcher$expectWeaklyReachable(kotlin.Any, kotlin.String)/watchedObject) to become weakly reachable soon. If not, [watchedObject](expect-weakly-reachable.md#leakcanary.ReachabilityWatcher$expectWeaklyReachable(kotlin.Any, kotlin.String)/watchedObject) will be considered retained. |

### Inheritors

| Name | Summary |
|---|---|
| [ObjectWatcher](../-object-watcher/index.md) | `class ObjectWatcher : `[`ReachabilityWatcher`](./index.md)<br>[ObjectWatcher](../-object-watcher/index.md) can be passed objects to [watch](../-object-watcher/watch.md). It will create [KeyedWeakReference](../-keyed-weak-reference/index.md) instances that reference watches objects, and check if those references have been cleared as expected on the [checkRetainedExecutor](#) executor. If not, these objects are considered retained and [ObjectWatcher](../-object-watcher/index.md) will then notify registered [OnObjectRetainedListener](../-on-object-retained-listener/index.md)s on that executor thread. |
