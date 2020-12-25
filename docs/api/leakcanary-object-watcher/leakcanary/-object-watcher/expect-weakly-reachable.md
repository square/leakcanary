[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [ObjectWatcher](index.md) / [expectWeaklyReachable](./expect-weakly-reachable.md)

# expectWeaklyReachable

`@Synchronized fun expectWeaklyReachable(watchedObject: `[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`, description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Overrides [ReachabilityWatcher.expectWeaklyReachable](../-reachability-watcher/expect-weakly-reachable.md)

Expects the provided [watchedObject](../-reachability-watcher/expect-weakly-reachable.md#leakcanary.ReachabilityWatcher$expectWeaklyReachable(kotlin.Any, kotlin.String)/watchedObject) to become weakly reachable soon. If not,
[watchedObject](../-reachability-watcher/expect-weakly-reachable.md#leakcanary.ReachabilityWatcher$expectWeaklyReachable(kotlin.Any, kotlin.String)/watchedObject) will be considered retained.

