[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [ActivityWatcher](./index.md)

# ActivityWatcher

`class ActivityWatcher : `[`InstallableWatcher`](../-installable-watcher/index.md)

Expects activities to become weakly reachable soon after they receive the [Activity.onDestroy](#)
callback.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `ActivityWatcher(application: Application, reachabilityWatcher: ReachabilityWatcher)`<br>Expects activities to become weakly reachable soon after they receive the [Activity.onDestroy](#) callback. |

### Functions

| Name | Summary |
|---|---|
| [install](install.md) | `fun install(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [uninstall](uninstall.md) | `fun uninstall(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
