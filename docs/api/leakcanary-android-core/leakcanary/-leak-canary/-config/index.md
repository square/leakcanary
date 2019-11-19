[leakcanary-android-core](../../../index.md) / [leakcanary](../../index.md) / [LeakCanary](../index.md) / [Config](./index.md)

# Config

`data class Config`

LeakCanary configuration data class. Properties can be updated via [copy](#).

**See Also**

[config](../config.md)

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `Config(dumpHeap: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, dumpHeapWhenDebugging: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = false, retainedVisibleThreshold: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)` = 5, referenceMatchers: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ReferenceMatcher> = AndroidReferenceMatchers.appDefaults, objectInspectors: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ObjectInspector> = AndroidObjectInspectors.appDefaults, onHeapAnalyzedListener: `[`OnHeapAnalyzedListener`](../../-on-heap-analyzed-listener/index.md)` = DefaultOnHeapAnalyzedListener.create(), metatadaExtractor: MetadataExtractor = AndroidMetadataExtractor, computeRetainedHeapSize: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, maxStoredHeapDumps: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)` = 7, requestWriteExternalStoragePermission: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = false, useExperimentalLeakFinders: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = false)`<br>LeakCanary configuration data class. Properties can be updated via [copy](#). |

### Properties

| Name | Summary |
|---|---|
| [computeRetainedHeapSize](compute-retained-heap-size.md) | `val computeRetainedHeapSize: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether to compute the retained heap size, which is the total number of bytes in memory that would be reclaimed if the detected leaks didn't happen. This includes native memory associated to Java objects (e.g. Android bitmaps). |
| [dumpHeap](dump-heap.md) | `val dumpHeap: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether LeakCanary should dump the heap when enough retained instances are found. This needs to be true for LeakCanary to work, but sometimes you may want to temporarily disable LeakCanary (e.g. for a product demo). |
| [dumpHeapWhenDebugging](dump-heap-when-debugging.md) | `val dumpHeapWhenDebugging: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>If [dumpHeapWhenDebugging](dump-heap-when-debugging.md) is false then LeakCanary will not dump the heap when the debugger is attached. The debugger can create temporary memory leaks (for instance if a thread is blocked on a breakpoint). |
| [maxStoredHeapDumps](max-stored-heap-dumps.md) | `val maxStoredHeapDumps: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>How many heap dumps are kept on the Android device for this app package. When this threshold is reached LeakCanary deletes the older heap dumps. As several heap dumps may be enqueued you should avoid going down to 1 or 2. |
| [metatadaExtractor](metatada-extractor.md) | `val metatadaExtractor: MetadataExtractor`<br>Extracts metadata from a hprof to be reported in [HeapAnalysisSuccess.metadata](#). Called on a background thread during heap analysis. |
| [objectInspectors](object-inspectors.md) | `val objectInspectors: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ObjectInspector>`<br>List of [ObjectInspector](#) that provide LeakCanary with insights about objects found in the heap. You can create your own [ObjectInspector](#) implementations, and also add a [shark.AppSingletonInspector](#) instance created with the list of internal singletons. |
| [onHeapAnalyzedListener](on-heap-analyzed-listener.md) | `val onHeapAnalyzedListener: `[`OnHeapAnalyzedListener`](../../-on-heap-analyzed-listener/index.md)<br>Called on a background thread when the heap analysis is complete. If you want leaks to be added to the activity that lists leaks, make sure to delegate calls to a [DefaultOnHeapAnalyzedListener](../../-default-on-heap-analyzed-listener/index.md). |
| [referenceMatchers](reference-matchers.md) | `val referenceMatchers: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ReferenceMatcher>`<br>Known patterns of references in the heap, lister here either to ignore them ([IgnoredReferenceMatcher](#)) or to mark them as library leaks ([LibraryLeakReferenceMatcher](#)). |
| [requestWriteExternalStoragePermission](request-write-external-storage-permission.md) | `val requestWriteExternalStoragePermission: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>LeakCanary always attempts to store heap dumps on the external storage if the WRITE_EXTERNAL_STORAGE is already granted, and otherwise uses the app storage. If the WRITE_EXTERNAL_STORAGE permission is not granted and [requestWriteExternalStoragePermission](request-write-external-storage-permission.md) is true, then LeakCanary will display a notification to ask for that permission. |
| [retainedVisibleThreshold](retained-visible-threshold.md) | `val retainedVisibleThreshold: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>When the app is visible, LeakCanary will wait for at least [retainedVisibleThreshold](retained-visible-threshold.md) retained instances before dumping the heap. Dumping the heap freezes the UI and can be frustrating for developers who are trying to work. This is especially frustrating as the Android Framework has a number of leaks that cannot easily be fixed. |
| [useExperimentalLeakFinders](use-experimental-leak-finders.md) | `val useExperimentalLeakFinders: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>When true, [objectInspectors](object-inspectors.md) are used to find leaks instead of only checking instances tracked by [KeyedWeakReference](#). This leads to finding more leaks and shorter leak traces. However this also means the same leaking instances will be found in every heap dump for a given process life. |
