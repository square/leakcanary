//[leakcanary-object-watcher](../../../index.md)/[leakcanary](../index.md)/[ReachabilityWatcher](index.md)/[expectWeaklyReachable](expect-weakly-reachable.md)

# expectWeaklyReachable

[jvm]\
abstract fun [expectWeaklyReachable](expect-weakly-reachable.md)(watchedObject: [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), description: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html))

Expects the provided [watchedObject](expect-weakly-reachable.md) to become weakly reachable soon. If not, [watchedObject](expect-weakly-reachable.md) will be considered retained.
