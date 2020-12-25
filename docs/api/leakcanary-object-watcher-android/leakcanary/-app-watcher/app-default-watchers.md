[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [AppWatcher](index.md) / [appDefaultWatchers](./app-default-watchers.md)

# appDefaultWatchers

`fun appDefaultWatchers(application: Application, reachabilityWatcher: ReachabilityWatcher = objectWatcher): `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`InstallableWatcher`](../-installable-watcher/index.md)`>`

Creates a new list of default app [InstallableWatcher](../-installable-watcher/index.md), created with the passed in
[reachabilityWatcher](app-default-watchers.md#leakcanary.AppWatcher$appDefaultWatchers(android.app.Application, leakcanary.ReachabilityWatcher)/reachabilityWatcher) (which defaults to [objectWatcher](object-watcher.md)). Once installed,
these watchers will pass in to [reachabilityWatcher](app-default-watchers.md#leakcanary.AppWatcher$appDefaultWatchers(android.app.Application, leakcanary.ReachabilityWatcher)/reachabilityWatcher) objects that they expect to become
weakly reachable.

The passed in [reachabilityWatcher](app-default-watchers.md#leakcanary.AppWatcher$appDefaultWatchers(android.app.Application, leakcanary.ReachabilityWatcher)/reachabilityWatcher) should probably delegate to [objectWatcher](object-watcher.md) but can
be used to filter out specific instances.

