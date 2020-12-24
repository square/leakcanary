

### All Types

| Name | Summary |
|---|---|
| [leakcanary.ActivityWatcher](../leakcanary/-activity-watcher/index.md) | Expects activities to become weakly reachable soon after they receive the [Activity.onDestroy](#) callback. |
| [leakcanary.AppWatcher](../leakcanary/-app-watcher/index.md) | The entry point API for using [ObjectWatcher](#) in an Android app. [AppWatcher.objectWatcher](../leakcanary/-app-watcher/object-watcher.md) is in charge of detecting retained objects, and [AppWatcher](../leakcanary/-app-watcher/index.md) is auto configured on app start to pass it activity and fragment instances. Call [ObjectWatcher.watch](#) on [objectWatcher](../leakcanary/-app-watcher/object-watcher.md) to watch any other object that you expect to be unreachable. |
| [leakcanary.FragmentAndViewModelWatcher](../leakcanary/-fragment-and-view-model-watcher/index.md) | Expects: |
| [leakcanary.InstallableWatcher](../leakcanary/-installable-watcher/index.md) |  |
| [leakcanary.RootViewWatcher](../leakcanary/-root-view-watcher/index.md) | Expects root views to become weakly reachable soon after they are removed from the window manager. |
| [leakcanary.ServiceWatcher](../leakcanary/-service-watcher/index.md) | Expects services to become weakly reachable soon after they receive the [Service.onDestroy](#) callback. |
