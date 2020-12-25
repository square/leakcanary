[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [ServiceWatcher](./index.md)

# ServiceWatcher

`class ServiceWatcher : `[`InstallableWatcher`](../-installable-watcher/index.md)

Expects services to become weakly reachable soon after they receive the [Service.onDestroy](#)
callback.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `ServiceWatcher(reachabilityWatcher: ReachabilityWatcher)`<br>Expects services to become weakly reachable soon after they receive the [Service.onDestroy](#) callback. |

### Functions

| Name | Summary |
|---|---|
| [install](install.md) | `fun install(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [uninstall](uninstall.md) | `fun uninstall(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
