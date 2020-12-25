[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [HeapAnalysisConfig](./index.md)

# HeapAnalysisConfig

`data class HeapAnalysisConfig`

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `HeapAnalysisConfig(referenceMatchers: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ReferenceMatcher> = AndroidReferenceMatchers.appDefaults, objectInspectors: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ObjectInspector> = AndroidObjectInspectors.appDefaults, metadataExtractor: MetadataExtractor = AndroidMetadataExtractor, computeRetainedHeapSize: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, leakingObjectFinder: LeakingObjectFinder = FilteringLeakingObjectFinder(
    AndroidObjectInspectors.appLeakingObjectFilters
  ), stripHeapDump: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = false)` |

### Properties

| Name | Summary |
|---|---|
| [computeRetainedHeapSize](compute-retained-heap-size.md) | `val computeRetainedHeapSize: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether to compute the retained heap size, which is the total number of bytes in memory that would be reclaimed if the detected leaks didn't happen. This includes native memory associated to Java objects (e.g. Android bitmaps). |
| [leakingObjectFinder](leaking-object-finder.md) | `val leakingObjectFinder: LeakingObjectFinder`<br>Finds the objects that are leaking, for which LeakCanary will compute leak traces. |
| [metadataExtractor](metadata-extractor.md) | `val metadataExtractor: MetadataExtractor`<br>Extracts metadata from a hprof to be reported in [shark.HeapAnalysisSuccess.metadata](#). Called on a background thread during heap analysis. |
| [objectInspectors](object-inspectors.md) | `val objectInspectors: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ObjectInspector>`<br>List of [ObjectInspector](#) that provide LeakCanary with insights about objects found in the heap. You can create your own [ObjectInspector](#) implementations, and also add a [shark.AppSingletonInspector](#) instance created with the list of internal singletons. |
| [referenceMatchers](reference-matchers.md) | `val referenceMatchers: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ReferenceMatcher>`<br>Known patterns of references in the heap, added here either to ignore them ([IgnoredReferenceMatcher](#)) or to mark them as library leaks ([LibraryLeakReferenceMatcher](#)). |
| [stripHeapDump](strip-heap-dump.md) | `val stripHeapDump: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether the first step after a heap dump should be to replace the content of all arrays with zeroes. This increases the overall processing time but limits the amount of time the heap dump exists on disk with potential PII. |
