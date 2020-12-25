[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [FragmentAndViewModelWatcher](./index.md)

# FragmentAndViewModelWatcher

`class FragmentAndViewModelWatcher : `[`InstallableWatcher`](../-installable-watcher/index.md)

Expects:

* Fragments (Support Library, Android X and AOSP) to become weakly reachable soon after they
receive the Fragment#onDestroy() callback.
* Fragment views (Support Library, Android X and AOSP) to become weakly reachable soon after
fragments receive the Fragment#onDestroyView() callback.
* Android X view models (both activity and fragment view models) to become weakly reachable soon
after they received the ViewModel#onCleared() callback.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `FragmentAndViewModelWatcher(application: Application, reachabilityWatcher: ReachabilityWatcher)`<br>Expects: |

### Functions

| Name | Summary |
|---|---|
| [install](install.md) | `fun install(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [uninstall](uninstall.md) | `fun uninstall(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
