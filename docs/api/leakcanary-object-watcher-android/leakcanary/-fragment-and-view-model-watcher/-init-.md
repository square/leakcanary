[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [FragmentAndViewModelWatcher](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`FragmentAndViewModelWatcher(application: Application, reachabilityWatcher: ReachabilityWatcher)`

Expects:

* Fragments (Support Library, Android X and AOSP) to become weakly reachable soon after they
receive the Fragment#onDestroy() callback.
* Fragment views (Support Library, Android X and AOSP) to become weakly reachable soon after
fragments receive the Fragment#onDestroyView() callback.
* Android X view models (both activity and fragment view models) to become weakly reachable soon
after they received the ViewModel#onCleared() callback.
