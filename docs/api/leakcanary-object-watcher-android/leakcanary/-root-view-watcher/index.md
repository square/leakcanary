[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [RootViewWatcher](./index.md)

# RootViewWatcher

`class RootViewWatcher : `[`InstallableWatcher`](../-installable-watcher/index.md)

Expects root views to become weakly reachable soon after they are removed from the window
manager.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `RootViewWatcher(reachabilityWatcher: ReachabilityWatcher)`<br>Expects root views to become weakly reachable soon after they are removed from the window manager. |

### Functions

| Name | Summary |
|---|---|
| [install](install.md) | `fun install(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [uninstall](uninstall.md) | `fun uninstall(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
