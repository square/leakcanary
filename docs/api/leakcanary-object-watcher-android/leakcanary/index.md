[leakcanary-object-watcher-android](../index.md) / [leakcanary](./index.md)

## Package leakcanary

### Types

| Name | Summary |
|---|---|
| [ActivityWatcher](-activity-watcher/index.md) | `class ActivityWatcher : `[`InstallableWatcher`](-installable-watcher/index.md)<br>Expects activities to become weakly reachable soon after they receive the [Activity.onDestroy](#) callback. |
| [AppWatcher](-app-watcher/index.md) | `object AppWatcher`<br>The entry point API for using [ObjectWatcher](#) in an Android app. [AppWatcher.objectWatcher](-app-watcher/object-watcher.md) is in charge of detecting retained objects, and [AppWatcher](-app-watcher/index.md) is auto configured on app start to pass it activity and fragment instances. Call [ObjectWatcher.watch](#) on [objectWatcher](-app-watcher/object-watcher.md) to watch any other object that you expect to be unreachable. |
| [FragmentAndViewModelWatcher](-fragment-and-view-model-watcher/index.md) | `class FragmentAndViewModelWatcher : `[`InstallableWatcher`](-installable-watcher/index.md)<br>Expects: |
| [InstallableWatcher](-installable-watcher/index.md) | `interface InstallableWatcher` |
| [RootViewWatcher](-root-view-watcher/index.md) | `class RootViewWatcher : `[`InstallableWatcher`](-installable-watcher/index.md)<br>Expects root views to become weakly reachable soon after they are removed from the window manager. |
| [ServiceWatcher](-service-watcher/index.md) | `class ServiceWatcher : `[`InstallableWatcher`](-installable-watcher/index.md)<br>Expects services to become weakly reachable soon after they receive the [Service.onDestroy](#) callback. |
