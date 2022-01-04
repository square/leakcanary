//[leakcanary-object-watcher-android-core](../../index.md)/[leakcanary](index.md)

# Package leakcanary

## Types

| Name | Summary |
|---|---|
| [ActivityWatcher](-activity-watcher/index.md) | [androidJvm]<br>class [ActivityWatcher](-activity-watcher/index.md)(application: [Application](https://developer.android.com/reference/kotlin/android/app/Application.html), reachabilityWatcher: ReachabilityWatcher) : [InstallableWatcher](-installable-watcher/index.md)<br>Expects activities to become weakly reachable soon after they receive the [Activity.onDestroy](https://developer.android.com/reference/kotlin/android/app/Activity.html#ondestroy) callback. |
| [AppWatcher](-app-watcher/index.md) | [androidJvm]<br>object [AppWatcher](-app-watcher/index.md)<br>The entry point API for using ObjectWatcher in an Android app. [AppWatcher.objectWatcher](-app-watcher/object-watcher.md) is in charge of detecting retained objects, and [AppWatcher](-app-watcher/index.md) is auto configured on app start to pass it activity and fragment instances. Call ObjectWatcher.watch on [objectWatcher](-app-watcher/object-watcher.md) to watch any other object that you expect to be unreachable. |
| [FragmentAndViewModelWatcher](-fragment-and-view-model-watcher/index.md) | [androidJvm]<br>class [FragmentAndViewModelWatcher](-fragment-and-view-model-watcher/index.md)(application: [Application](https://developer.android.com/reference/kotlin/android/app/Application.html), reachabilityWatcher: ReachabilityWatcher) : [InstallableWatcher](-installable-watcher/index.md)<br>Expects: |
| [InstallableWatcher](-installable-watcher/index.md) | [androidJvm]<br>interface [InstallableWatcher](-installable-watcher/index.md) |
| [RootViewWatcher](-root-view-watcher/index.md) | [androidJvm]<br>class [RootViewWatcher](-root-view-watcher/index.md)(reachabilityWatcher: ReachabilityWatcher) : [InstallableWatcher](-installable-watcher/index.md)<br>Expects root views to become weakly reachable soon after they are removed from the window manager. |
| [ServiceWatcher](-service-watcher/index.md) | [androidJvm]<br>class [ServiceWatcher](-service-watcher/index.md)(reachabilityWatcher: ReachabilityWatcher) : [InstallableWatcher](-installable-watcher/index.md)<br>Expects services to become weakly reachable soon after they receive the [Service.onDestroy](https://developer.android.com/reference/kotlin/android/app/Service.html#ondestroy) callback. |
