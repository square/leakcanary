//[leakcanary-android-core](../../../../../index.md)/[leakcanary](../../../index.md)/[LeakCanary](../../index.md)/[Config](../index.md)/[Builder](index.md)

# Builder

[androidJvm]\
class [Builder](index.md)

Builder for [LeakCanary.Config](../index.md) intended to be used only from Java code.

Usage:

LeakCanary.Config config = LeakCanary.getConfig().newBuilder()\
   .retainedVisibleThreshold(3)\
   .build();\
LeakCanary.setConfig(config);

For idiomatic Kotlin use copy() method instead:

LeakCanary.config = LeakCanary.config.copy(retainedVisibleThreshold = 3)

## Functions

| Name | Summary |
|---|---|
| [build](build.md) | [androidJvm]<br>fun [build](build.md)(): [LeakCanary.Config](../index.md) |
| [computeRetainedHeapSize](compute-retained-heap-size.md) | [androidJvm]<br>fun [computeRetainedHeapSize](compute-retained-heap-size.md)(computeRetainedHeapSize: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)): [LeakCanary.Config.Builder](index.md) |
| [dumpHeap](dump-heap.md) | [androidJvm]<br>fun [dumpHeap](dump-heap.md)(dumpHeap: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)): [LeakCanary.Config.Builder](index.md) |
| [dumpHeapWhenDebugging](dump-heap-when-debugging.md) | [androidJvm]<br>fun [dumpHeapWhenDebugging](dump-heap-when-debugging.md)(dumpHeapWhenDebugging: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)): [LeakCanary.Config.Builder](index.md) |
| [eventListeners](event-listeners.md) | [androidJvm]<br>fun [eventListeners](event-listeners.md)(eventListeners: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[EventListener](../../../-event-listener/index.md)&gt;): [LeakCanary.Config.Builder](index.md) |
| [heapDumper](heap-dumper.md) | [androidJvm]<br>fun [heapDumper](heap-dumper.md)(heapDumper: [HeapDumper](../../../-heap-dumper/index.md)): [LeakCanary.Config.Builder](index.md) |
| [leakingObjectFinder](leaking-object-finder.md) | [androidJvm]<br>fun [leakingObjectFinder](leaking-object-finder.md)(leakingObjectFinder: LeakingObjectFinder): [LeakCanary.Config.Builder](index.md) |
| [maxStoredHeapDumps](max-stored-heap-dumps.md) | [androidJvm]<br>fun [maxStoredHeapDumps](max-stored-heap-dumps.md)(maxStoredHeapDumps: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [LeakCanary.Config.Builder](index.md) |
| [metadataExtractor](metadata-extractor.md) | [androidJvm]<br>fun [metadataExtractor](metadata-extractor.md)(metadataExtractor: MetadataExtractor): [LeakCanary.Config.Builder](index.md) |
| [objectInspectors](object-inspectors.md) | [androidJvm]<br>fun [objectInspectors](object-inspectors.md)(objectInspectors: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ObjectInspector&gt;): [LeakCanary.Config.Builder](index.md) |
| [referenceMatchers](reference-matchers.md) | [androidJvm]<br>fun [referenceMatchers](reference-matchers.md)(referenceMatchers: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ReferenceMatcher&gt;): [LeakCanary.Config.Builder](index.md) |
| [requestWriteExternalStoragePermission](request-write-external-storage-permission.md) | [androidJvm]<br>fun [requestWriteExternalStoragePermission](request-write-external-storage-permission.md)(requestWriteExternalStoragePermission: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)): [LeakCanary.Config.Builder](index.md) |
| [retainedVisibleThreshold](retained-visible-threshold.md) | [androidJvm]<br>fun [retainedVisibleThreshold](retained-visible-threshold.md)(retainedVisibleThreshold: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [LeakCanary.Config.Builder](index.md) |
