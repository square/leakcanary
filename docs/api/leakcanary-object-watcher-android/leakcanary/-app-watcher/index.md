[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [AppWatcher](./index.md)

# AppWatcher

`object AppWatcher`

The entry point API for using [ObjectWatcher](#) in an Android app. [AppWatcher.objectWatcher](object-watcher.md) is
in charge of detecting retained objects, and [AppWatcher](./index.md) is auto configured on app start to
pass it activity and fragment instances. Call [ObjectWatcher.watch](#) on [objectWatcher](object-watcher.md) to
watch any other object that you expect to be unreachable.

### Types

| Name | Summary |
|---|---|
| [Config](-config/index.md) | `data class ~~Config~~` |

### Properties

| Name | Summary |
|---|---|
| [config](config.md) | `var ~~config~~: `[`AppWatcher.Config`](-config/index.md) |
| [isInstalled](is-installed.md) | `val isInstalled: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [objectWatcher](object-watcher.md) | `val objectWatcher: ObjectWatcher`<br>The [ObjectWatcher](#) used by AppWatcher to detect retained objects. Only set when [isInstalled](is-installed.md) is true. |

### Functions

| Name | Summary |
|---|---|
| [appDefaultWatchers](app-default-watchers.md) | `fun appDefaultWatchers(application: Application, reachabilityWatcher: ReachabilityWatcher = objectWatcher): `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`InstallableWatcher`](../-installable-watcher/index.md)`>`<br>Creates a new list of default app [InstallableWatcher](../-installable-watcher/index.md), created with the passed in [reachabilityWatcher](app-default-watchers.md#leakcanary.AppWatcher$appDefaultWatchers(android.app.Application, leakcanary.ReachabilityWatcher)/reachabilityWatcher) (which defaults to [objectWatcher](object-watcher.md)). Once installed, these watchers will pass in to [reachabilityWatcher](app-default-watchers.md#leakcanary.AppWatcher$appDefaultWatchers(android.app.Application, leakcanary.ReachabilityWatcher)/reachabilityWatcher) objects that they expect to become weakly reachable. |
| [manualInstall](manual-install.md) | `fun manualInstall(application: Application, retainedDelayMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = TimeUnit.SECONDS.toMillis(5), watchersToInstall: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`InstallableWatcher`](../-installable-watcher/index.md)`> = appDefaultWatchers(application)): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Enables usage of [AppWatcher.objectWatcher](object-watcher.md) which will expect passed in objects to become weakly reachable within [retainedDelayMillis](manual-install.md#leakcanary.AppWatcher$manualInstall(android.app.Application, kotlin.Long, kotlin.collections.List((leakcanary.InstallableWatcher)))/retainedDelayMillis) ms and if not will trigger LeakCanary (if LeakCanary is in the classpath). |
