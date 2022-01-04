//[leakcanary-object-watcher-android-core](../../../index.md)/[leakcanary](../index.md)/[FragmentAndViewModelWatcher](index.md)

# FragmentAndViewModelWatcher

[androidJvm]\
class [FragmentAndViewModelWatcher](index.md)(application: [Application](https://developer.android.com/reference/kotlin/android/app/Application.html), reachabilityWatcher: ReachabilityWatcher) : [InstallableWatcher](../-installable-watcher/index.md)

Expects:

- 
   Fragments (Support Library, Android X and AOSP) to become weakly reachable soon after they receive the Fragment#onDestroy() callback.
- 
   Fragment views (Support Library, Android X and AOSP) to become weakly reachable soon after fragments receive the Fragment#onDestroyView() callback.
- 
   Android X view models (both activity and fragment view models) to become weakly reachable soon after they received the ViewModel#onCleared() callback.

## Constructors

| | |
|---|---|
| [FragmentAndViewModelWatcher](-fragment-and-view-model-watcher.md) | [androidJvm]<br>fun [FragmentAndViewModelWatcher](-fragment-and-view-model-watcher.md)(application: [Application](https://developer.android.com/reference/kotlin/android/app/Application.html), reachabilityWatcher: ReachabilityWatcher) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [androidJvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [install](install.md) | [androidJvm]<br>open override fun [install](install.md)() |
| [uninstall](uninstall.md) | [androidJvm]<br>open override fun [uninstall](uninstall.md)() |
