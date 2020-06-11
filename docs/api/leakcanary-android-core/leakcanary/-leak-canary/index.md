[leakcanary-android-core](../../index.md) / [leakcanary](../index.md) / [LeakCanary](./index.md)

# LeakCanary

`object LeakCanary`

The entry point API for LeakCanary. LeakCanary builds on top of [AppWatcher](#). AppWatcher
notifies LeakCanary of retained instances, which in turns dumps the heap, analyses it and
publishes the results.

LeakCanary can be configured by updating [config](config.md).

### Types

| Name | Summary |
|---|---|
| [Config](-config/index.md) | `data class Config`<br>LeakCanary configuration data class. Properties can be updated via [copy](#). |

### Properties

| Name | Summary |
|---|---|
| [config](config.md) | `var config: `[`LeakCanary.Config`](-config/index.md)<br>The current LeakCanary configuration. Can be updated at any time, usually by replacing it with a mutated copy, e.g.: |

### Functions

| Name | Summary |
|---|---|
| [dumpHeap](dump-heap.md) | `fun dumpHeap(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Immediately triggers a heap dump and analysis, if there is at least one retained instance tracked by [AppWatcher.objectWatcher](#). If there are no retained instances then the heap will not be dumped and a notification will be shown instead. |
| [newLeakDisplayActivityIntent](new-leak-display-activity-intent.md) | `fun newLeakDisplayActivityIntent(): Intent`<br>Returns a new [Intent](#) that can be used to programmatically launch the leak display activity. |
| [showLeakDisplayActivityLauncherIcon](show-leak-display-activity-launcher-icon.md) | `fun showLeakDisplayActivityLauncherIcon(showLauncherIcon: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Dynamically shows / hides the launcher icon for the leak display activity. Note: you can change the default value by overriding the `leak_canary_add_launcher_icon` boolean resource: |
