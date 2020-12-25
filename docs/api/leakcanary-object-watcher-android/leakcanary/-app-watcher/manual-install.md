[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [AppWatcher](index.md) / [manualInstall](./manual-install.md)

# manualInstall

`@JvmOverloads fun manualInstall(application: Application, retainedDelayMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = TimeUnit.SECONDS.toMillis(5), watchersToInstall: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`InstallableWatcher`](../-installable-watcher/index.md)`> = appDefaultWatchers(application)): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Enables usage of [AppWatcher.objectWatcher](object-watcher.md) which will expect passed in objects to become
weakly reachable within [retainedDelayMillis](manual-install.md#leakcanary.AppWatcher$manualInstall(android.app.Application, kotlin.Long, kotlin.collections.List((leakcanary.InstallableWatcher)))/retainedDelayMillis) ms and if not will trigger LeakCanary (if
LeakCanary is in the classpath).

In the main process, this method is automatically called with default parameter values  on app
startup. You can call this method directly to customize the installation, however you must
first disable the automatic call by overriding the `leak_canary_watcher_auto_install` boolean
resource:

```
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <bool name="leak_canary_watcher_auto_install">false</bool>
</resources>
```

[watchersToInstall](manual-install.md#leakcanary.AppWatcher$manualInstall(android.app.Application, kotlin.Long, kotlin.collections.List((leakcanary.InstallableWatcher)))/watchersToInstall) can be customized to a subset of the default app watchers:

```
val watchersToInstall = AppWatcher.appDefaultWatchers(application)
  .filter { it !is RootViewWatcher }
AppWatcher.manualInstall(
  application = application,
  watchersToInstall = watchersToInstall
)
```

[watchersToInstall](manual-install.md#leakcanary.AppWatcher$manualInstall(android.app.Application, kotlin.Long, kotlin.collections.List((leakcanary.InstallableWatcher)))/watchersToInstall) can also be customized to ignore specific instances (e.g. here ignoring
leaks of BadSdkLeakingFragment):

```
val watchersToInstall = AppWatcher.appDefaultWatchers(application, ReachabilityWatcher { watchedObject, description ->
  if (watchedObject !is BadSdkLeakingFragment) {
    AppWatcher.objectWatcher.expectWeaklyReachable(watchedObject, description)
  }
})
AppWatcher.manualInstall(
  application = application,
  watchersToInstall = watchersToInstall
)
```

