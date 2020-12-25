[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [InstallableWatcher](./index.md)

# InstallableWatcher

`interface InstallableWatcher`

### Functions

| Name | Summary |
|---|---|
| [install](install.md) | `abstract fun install(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [uninstall](uninstall.md) | `abstract fun uninstall(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |

### Inheritors

| Name | Summary |
|---|---|
| [ActivityWatcher](../-activity-watcher/index.md) | `class ActivityWatcher : `[`InstallableWatcher`](./index.md)<br>Expects activities to become weakly reachable soon after they receive the [Activity.onDestroy](#) callback. |
| [FragmentAndViewModelWatcher](../-fragment-and-view-model-watcher/index.md) | `class FragmentAndViewModelWatcher : `[`InstallableWatcher`](./index.md)<br>Expects: |
| [RootViewWatcher](../-root-view-watcher/index.md) | `class RootViewWatcher : `[`InstallableWatcher`](./index.md)<br>Expects root views to become weakly reachable soon after they are removed from the window manager. |
| [ServiceWatcher](../-service-watcher/index.md) | `class ServiceWatcher : `[`InstallableWatcher`](./index.md)<br>Expects services to become weakly reachable soon after they receive the [Service.onDestroy](#) callback. |
