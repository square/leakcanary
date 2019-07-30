[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [AppWatcher](index.md) / [manualInstall](./manual-install.md)

# manualInstall

`fun manualInstall(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

[AppWatcher](index.md) is automatically installed on main process start by
[leakcanary.internal.AppWatcherInstaller](#) which is registered in the AndroidManifest.xml of
your app. If you disabled [leakcanary.internal.AppWatcherInstaller](#) or you need AppWatcher
or LeakCanary to run outside of the main process then you can call this method to install
[AppWatcher](index.md).

