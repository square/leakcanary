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
| [Config](-config/index.md) | `data class Config`<br>AppWatcher configuration data class. Properties can be updated via [copy](#). |

### Properties

| Name | Summary |
|---|---|
| [config](config.md) | `var config: `[`AppWatcher.Config`](-config/index.md)<br>The current AppWatcher configuration. Can be updated at any time, usually by replacing it with a mutated copy, e.g.: |
| [isInstalled](is-installed.md) | `val isInstalled: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [objectWatcher](object-watcher.md) | `val objectWatcher: ObjectWatcher`<br>The [ObjectWatcher](#) used by AppWatcher to detect retained objects. |

### Functions

| Name | Summary |
|---|---|
| [manualInstall](manual-install.md) | `fun manualInstall(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>[AppWatcher](./index.md) is automatically installed on main process start by [leakcanary.internal.AppWatcherInstaller](#) which is registered in the AndroidManifest.xml of your app. If you disabled [leakcanary.internal.AppWatcherInstaller](#) or you need AppWatcher or LeakCanary to run outside of the main process then you can call this method to install [AppWatcher](./index.md). |
