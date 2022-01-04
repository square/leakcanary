//[leakcanary-android-core](../../../../index.md)/[leakcanary](../../index.md)/[LeakCanary](../index.md)/[Config](index.md)

# Config

[androidJvm]\
data class [Config](index.md)(dumpHeap: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html), dumpHeapWhenDebugging: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html), retainedVisibleThreshold: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), referenceMatchers: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ReferenceMatcher&gt;, objectInspectors: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ObjectInspector&gt;, onHeapAnalyzedListener: OnHeapAnalyzedListener, metadataExtractor: MetadataExtractor, computeRetainedHeapSize: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html), maxStoredHeapDumps: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), requestWriteExternalStoragePermission: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html), leakingObjectFinder: LeakingObjectFinder, heapDumper: [HeapDumper](../../-heap-dumper/index.md), eventListeners: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[EventListener](../../-event-listener/index.md)&gt;, useExperimentalLeakFinders: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html))

LeakCanary configuration data class. Properties can be updated via copy.

## See also

androidJvm

| | |
|---|---|
| [leakcanary.LeakCanary](../config.md) |  |

## Constructors

| | |
|---|---|
| [Config](-config.md) | [androidJvm]<br>fun [Config](-config.md)(dumpHeap: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = true, dumpHeapWhenDebugging: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = false, retainedVisibleThreshold: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) = 5, referenceMatchers: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ReferenceMatcher&gt; = AndroidReferenceMatchers.appDefaults, objectInspectors: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ObjectInspector&gt; = AndroidObjectInspectors.appDefaults, onHeapAnalyzedListener: OnHeapAnalyzedListener = DefaultOnHeapAnalyzedListener.create(), metadataExtractor: MetadataExtractor = AndroidMetadataExtractor, computeRetainedHeapSize: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = true, maxStoredHeapDumps: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) = 7, requestWriteExternalStoragePermission: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = false, leakingObjectFinder: LeakingObjectFinder = KeyedWeakReferenceFinder, heapDumper: [HeapDumper](../../-heap-dumper/index.md) = AndroidDebugHeapDumper, eventListeners: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[EventListener](../../-event-listener/index.md)&gt; = listOf(       LogcatEventListener,       ToastEventListener,       if (InternalLeakCanary.formFactor == TV) TvEventListener else NotificationEventListener,       when {           RemoteWorkManagerHeapAnalyzer.remoteLeakCanaryServiceInClasspath -&gt;             RemoteWorkManagerHeapAnalyzer           WorkManagerHeapAnalyzer.workManagerInClasspath -&gt; WorkManagerHeapAnalyzer           else -&gt; BackgroundThreadHeapAnalyzer       }     ), useExperimentalLeakFinders: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = false) |

## Types

| Name | Summary |
|---|---|
| [Builder](-builder/index.md) | [androidJvm]<br>class [Builder](-builder/index.md)<br>Builder for [LeakCanary.Config](index.md) intended to be used only from Java code. |

## Functions

| Name | Summary |
|---|---|
| [newBuilder](new-builder.md) | [androidJvm]<br>fun [newBuilder](new-builder.md)(): [LeakCanary.Config.Builder](-builder/index.md)<br>Construct a new Config via [LeakCanary.Config.Builder](-builder/index.md). Note: this method is intended to be used from Java code only. For idiomatic Kotlin use copy() to modify [LeakCanary.config](../config.md).<br>Since Kotlin<br>999.9 |

## Properties

| Name | Summary |
|---|---|
| [computeRetainedHeapSize](compute-retained-heap-size.md) | [androidJvm]<br>val [computeRetainedHeapSize](compute-retained-heap-size.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = true<br>Whether to compute the retained heap size, which is the total number of bytes in memory that would be reclaimed if the detected leaks didn't happen. This includes native memory associated to Java objects (e.g. Android bitmaps). |
| [dumpHeap](dump-heap.md) | [androidJvm]<br>val [dumpHeap](dump-heap.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = true<br>Whether LeakCanary should dump the heap when enough retained instances are found. This needs to be true for LeakCanary to work, but sometimes you may want to temporarily disable LeakCanary (e.g. for a product demo). |
| [dumpHeapWhenDebugging](dump-heap-when-debugging.md) | [androidJvm]<br>val [dumpHeapWhenDebugging](dump-heap-when-debugging.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = false<br>If [dumpHeapWhenDebugging](dump-heap-when-debugging.md) is false then LeakCanary will not dump the heap when the debugger is attached. The debugger can create temporary memory leaks (for instance if a thread is blocked on a breakpoint). |
| [eventListeners](event-listeners.md) | [androidJvm]<br>val [eventListeners](event-listeners.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[EventListener](../../-event-listener/index.md)&gt;<br>Listeners for LeakCanary events. See [EventListener.Event](../../-event-listener/-event/index.md) for the list of events and which thread they're sent from. You most likely want to keep this list and add to it, or remove a few entries but not all entries. Each listener is independent and provides additional behavior which you can disable by not excluding it: |
| [heapDumper](heap-dumper.md) | [androidJvm]<br>val [heapDumper](heap-dumper.md): [HeapDumper](../../-heap-dumper/index.md)<br>Dumps the Java heap. You may replace this with your own implementation if you wish to change the core heap dumping implementation. |
| [leakingObjectFinder](leaking-object-finder.md) | [androidJvm]<br>val [leakingObjectFinder](leaking-object-finder.md): LeakingObjectFinder<br>Finds the objects that are leaking, for which LeakCanary will compute leak traces. |
| [maxStoredHeapDumps](max-stored-heap-dumps.md) | [androidJvm]<br>val [maxStoredHeapDumps](max-stored-heap-dumps.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) = 7<br>How many heap dumps are kept on the Android device for this app package. When this threshold is reached LeakCanary deletes the older heap dumps. As several heap dumps may be enqueued you should avoid going down to 1 or 2. |
| [metadataExtractor](metadata-extractor.md) | [androidJvm]<br>val [metadataExtractor](metadata-extractor.md): MetadataExtractor<br>Extracts metadata from a hprof to be reported in HeapAnalysisSuccess.metadata. Called on a background thread during heap analysis. |
| [objectInspectors](object-inspectors.md) | [androidJvm]<br>val [objectInspectors](object-inspectors.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ObjectInspector&gt;<br>List of ObjectInspector that provide LeakCanary with insights about objects found in the heap. You can create your own ObjectInspector implementations, and also add a shark.AppSingletonInspector instance created with the list of internal singletons. |
| [referenceMatchers](reference-matchers.md) | [androidJvm]<br>val [referenceMatchers](reference-matchers.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ReferenceMatcher&gt;<br>Known patterns of references in the heap, added here either to ignore them (IgnoredReferenceMatcher) or to mark them as library leaks (LibraryLeakReferenceMatcher). |
| [requestWriteExternalStoragePermission](request-write-external-storage-permission.md) | [androidJvm]<br>val [requestWriteExternalStoragePermission](request-write-external-storage-permission.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = false<br>LeakCanary always attempts to store heap dumps on the external storage if the WRITE_EXTERNAL_STORAGE is already granted, and otherwise uses the app storage. If the WRITE_EXTERNAL_STORAGE permission is not granted and [requestWriteExternalStoragePermission](request-write-external-storage-permission.md) is true, then LeakCanary will display a notification to ask for that permission. |
| [retainedVisibleThreshold](retained-visible-threshold.md) | [androidJvm]<br>val [retainedVisibleThreshold](retained-visible-threshold.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) = 5<br>When the app is visible, LeakCanary will wait for at least [retainedVisibleThreshold](retained-visible-threshold.md) retained instances before dumping the heap. Dumping the heap freezes the UI and can be frustrating for developers who are trying to work. This is especially frustrating as the Android Framework has a number of leaks that cannot easily be fixed. |
