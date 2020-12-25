[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [ReachabilityWatcher](index.md) / [expectWeaklyReachable](./expect-weakly-reachable.md)

# expectWeaklyReachable

`abstract fun expectWeaklyReachable(watchedObject: `[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`, description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Expects the provided [watchedObject](expect-weakly-reachable.md#leakcanary.ReachabilityWatcher$expectWeaklyReachable(kotlin.Any, kotlin.String)/watchedObject) to become weakly reachable soon. If not,
[watchedObject](expect-weakly-reachable.md#leakcanary.ReachabilityWatcher$expectWeaklyReachable(kotlin.Any, kotlin.String)/watchedObject) will be considered retained.

