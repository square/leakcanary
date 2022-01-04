//[leakcanary-android-core](../../../index.md)/[leakcanary](../index.md)/[LeakCanary](index.md)

# LeakCanary

[androidJvm]\
object [LeakCanary](index.md)

The entry point API for LeakCanary. LeakCanary builds on top of AppWatcher. AppWatcher notifies LeakCanary of retained instances, which in turns dumps the heap, analyses it and publishes the results.

LeakCanary can be configured by updating [config](config.md).

## Types

| Name | Summary |
|---|---|
| [Config](-config/index.md) | [androidJvm]<br>data class [Config](-config/index.md)(dumpHeap: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html), dumpHeapWhenDebugging: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html), retainedVisibleThreshold: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), referenceMatchers: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ReferenceMatcher&gt;, objectInspectors: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ObjectInspector&gt;, onHeapAnalyzedListener: OnHeapAnalyzedListener, metadataExtractor: MetadataExtractor, computeRetainedHeapSize: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html), maxStoredHeapDumps: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), requestWriteExternalStoragePermission: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html), leakingObjectFinder: LeakingObjectFinder, heapDumper: [HeapDumper](../-heap-dumper/index.md), eventListeners: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[EventListener](../-event-listener/index.md)&gt;, useExperimentalLeakFinders: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html))<br>LeakCanary configuration data class. Properties can be updated via copy. |

## Functions

| Name | Summary |
|---|---|
| [dumpHeap](dump-heap.md) | [androidJvm]<br>fun [dumpHeap](dump-heap.md)()<br>Immediately triggers a heap dump and analysis, if there is at least one retained instance tracked by AppWatcher.objectWatcher. If there are no retained instances then the heap will not be dumped and a notification will be shown instead. |
| [newLeakDisplayActivityIntent](new-leak-display-activity-intent.md) | [androidJvm]<br>fun [newLeakDisplayActivityIntent](new-leak-display-activity-intent.md)(): [Intent](https://developer.android.com/reference/kotlin/android/content/Intent.html)<br>Returns a new [Intent](https://developer.android.com/reference/kotlin/android/content/Intent.html) that can be used to programmatically launch the leak display activity. |
| [showLeakDisplayActivityLauncherIcon](show-leak-display-activity-launcher-icon.md) | [androidJvm]<br>fun [showLeakDisplayActivityLauncherIcon](show-leak-display-activity-launcher-icon.md)(showLauncherIcon: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html))<br>Dynamically shows / hides the launcher icon for the leak display activity. Note: you can change the default value by overriding the leak_canary_add_launcher_icon boolean resource: |

## Properties

| Name | Summary |
|---|---|
| [config](config.md) | [androidJvm]<br>@[JvmStatic](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-jvm-static/index.html)<br>@[Volatile](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-volatile/index.html)<br>var [config](config.md): [LeakCanary.Config](-config/index.md)<br>The current LeakCanary configuration. Can be updated at any time, usually by replacing it with a mutated copy, e.g.: |
