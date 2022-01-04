//[leakcanary-object-watcher-android-core](../../../index.md)/[leakcanary](../index.md)/[ActivityWatcher](index.md)

# ActivityWatcher

[androidJvm]\
class [ActivityWatcher](index.md)(application: [Application](https://developer.android.com/reference/kotlin/android/app/Application.html), reachabilityWatcher: ReachabilityWatcher) : [InstallableWatcher](../-installable-watcher/index.md)

Expects activities to become weakly reachable soon after they receive the [Activity.onDestroy](https://developer.android.com/reference/kotlin/android/app/Activity.html#ondestroy) callback.

## Constructors

| | |
|---|---|
| [ActivityWatcher](-activity-watcher.md) | [androidJvm]<br>fun [ActivityWatcher](-activity-watcher.md)(application: [Application](https://developer.android.com/reference/kotlin/android/app/Application.html), reachabilityWatcher: ReachabilityWatcher) |

## Functions

| Name | Summary |
|---|---|
| [install](install.md) | [androidJvm]<br>open override fun [install](install.md)() |
| [uninstall](uninstall.md) | [androidJvm]<br>open override fun [uninstall](uninstall.md)() |
