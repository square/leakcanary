//[leakcanary-object-watcher-android-core](../../../index.md)/[leakcanary](../index.md)/[AppWatcher](index.md)

# AppWatcher

[androidJvm]\
object [AppWatcher](index.md)

The entry point API for using ObjectWatcher in an Android app. [AppWatcher.objectWatcher](object-watcher.md) is in charge of detecting retained objects, and [AppWatcher](index.md) is auto configured on app start to pass it activity and fragment instances. Call ObjectWatcher.watch on [objectWatcher](object-watcher.md) to watch any other object that you expect to be unreachable.

## Functions

| Name | Summary |
|---|---|
| [appDefaultWatchers](app-default-watchers.md) | [androidJvm]<br>fun [appDefaultWatchers](app-default-watchers.md)(application: [Application](https://developer.android.com/reference/kotlin/android/app/Application.html), reachabilityWatcher: ReachabilityWatcher = objectWatcher): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[InstallableWatcher](../-installable-watcher/index.md)&gt;<br>Creates a new list of default app [InstallableWatcher](../-installable-watcher/index.md), created with the passed in [reachabilityWatcher](app-default-watchers.md) (which defaults to [objectWatcher](object-watcher.md)). Once installed, these watchers will pass in to [reachabilityWatcher](app-default-watchers.md) objects that they expect to become weakly reachable. |
| [manualInstall](manual-install.md) | [androidJvm]<br>@[JvmOverloads](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-jvm-overloads/index.html)<br>fun [manualInstall](manual-install.md)(application: [Application](https://developer.android.com/reference/kotlin/android/app/Application.html), retainedDelayMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) = TimeUnit.SECONDS.toMillis(5), watchersToInstall: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[InstallableWatcher](../-installable-watcher/index.md)&gt; = appDefaultWatchers(application))<br>Enables usage of [AppWatcher.objectWatcher](object-watcher.md) which will expect passed in objects to become weakly reachable within [retainedDelayMillis](manual-install.md) ms and if not will trigger LeakCanary (if LeakCanary is in the classpath). |

## Properties

| Name | Summary |
|---|---|
| [isInstalled](is-installed.md) | [androidJvm]<br>val [isInstalled](is-installed.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [objectWatcher](object-watcher.md) | [androidJvm]<br>val [objectWatcher](object-watcher.md): ObjectWatcher<br>The ObjectWatcher used by AppWatcher to detect retained objects. Only set when [isInstalled](is-installed.md) is true. |
| [retainedDelayMillis](retained-delay-millis.md) | [androidJvm]<br>@[Volatile](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-volatile/index.html)<br>var [retainedDelayMillis](retained-delay-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
