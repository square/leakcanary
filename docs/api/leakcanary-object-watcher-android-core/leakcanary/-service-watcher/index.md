//[leakcanary-object-watcher-android-core](../../../index.md)/[leakcanary](../index.md)/[ServiceWatcher](index.md)

# ServiceWatcher

[androidJvm]\
class [ServiceWatcher](index.md)(reachabilityWatcher: ReachabilityWatcher) : [InstallableWatcher](../-installable-watcher/index.md)

Expects services to become weakly reachable soon after they receive the [Service.onDestroy](https://developer.android.com/reference/kotlin/android/app/Service.html#ondestroy) callback.

## Constructors

| | |
|---|---|
| [ServiceWatcher](-service-watcher.md) | [androidJvm]<br>fun [ServiceWatcher](-service-watcher.md)(reachabilityWatcher: ReachabilityWatcher) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [androidJvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [install](install.md) | [androidJvm]<br>open override fun [install](install.md)() |
| [uninstall](uninstall.md) | [androidJvm]<br>open override fun [uninstall](uninstall.md)() |
