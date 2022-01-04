//[leakcanary-object-watcher-android-core](../../../index.md)/[leakcanary](../index.md)/[AppWatcher](index.md)/[appDefaultWatchers](app-default-watchers.md)

# appDefaultWatchers

[androidJvm]\
fun [appDefaultWatchers](app-default-watchers.md)(application: [Application](https://developer.android.com/reference/kotlin/android/app/Application.html), reachabilityWatcher: ReachabilityWatcher = objectWatcher): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[InstallableWatcher](../-installable-watcher/index.md)&gt;

Creates a new list of default app [InstallableWatcher](../-installable-watcher/index.md), created with the passed in [reachabilityWatcher](app-default-watchers.md) (which defaults to [objectWatcher](object-watcher.md)). Once installed, these watchers will pass in to [reachabilityWatcher](app-default-watchers.md) objects that they expect to become weakly reachable.

The passed in [reachabilityWatcher](app-default-watchers.md) should probably delegate to [objectWatcher](object-watcher.md) but can be used to filter out specific instances.
