//[leakcanary-object-watcher](../../../index.md)/[leakcanary](../index.md)/[ReachabilityWatcher](index.md)

# ReachabilityWatcher

[jvm]\
fun interface [ReachabilityWatcher](index.md)

## Functions

| Name | Summary |
|---|---|
| [expectWeaklyReachable](expect-weakly-reachable.md) | [jvm]<br>abstract fun [expectWeaklyReachable](expect-weakly-reachable.md)(watchedObject: [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), description: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html))<br>Expects the provided [watchedObject](expect-weakly-reachable.md) to become weakly reachable soon. If not, [watchedObject](expect-weakly-reachable.md) will be considered retained. |

## Inheritors

| Name |
|---|
| [ObjectWatcher](../-object-watcher/index.md) |
