//[leakcanary-android-release](../../../index.md)/[leakcanary](../index.md)/[HeapAnalysisConfig](index.md)

# HeapAnalysisConfig

[androidJvm]\
data class [HeapAnalysisConfig](index.md)(referenceMatchers: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ReferenceMatcher&gt;, objectInspectors: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ObjectInspector&gt;, metadataExtractor: MetadataExtractor, computeRetainedHeapSize: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html), leakingObjectFinder: LeakingObjectFinder, stripHeapDump: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html))

## Properties

| Name | Summary |
|---|---|
| [computeRetainedHeapSize](compute-retained-heap-size.md) | [androidJvm]<br>val [computeRetainedHeapSize](compute-retained-heap-size.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = true<br>Whether to compute the retained heap size, which is the total number of bytes in memory that would be reclaimed if the detected leaks didn't happen. This includes native memory associated to Java objects (e.g. Android bitmaps). |
| [leakingObjectFinder](leaking-object-finder.md) | [androidJvm]<br>val [leakingObjectFinder](leaking-object-finder.md): LeakingObjectFinder<br>Finds the objects that are leaking, for which LeakCanary will compute leak traces. |
| [metadataExtractor](metadata-extractor.md) | [androidJvm]<br>val [metadataExtractor](metadata-extractor.md): MetadataExtractor<br>Extracts metadata from a hprof to be reported in shark.HeapAnalysisSuccess.metadata. Called on a background thread during heap analysis. |
| [objectInspectors](object-inspectors.md) | [androidJvm]<br>val [objectInspectors](object-inspectors.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ObjectInspector&gt;<br>List of ObjectInspector that provide LeakCanary with insights about objects found in the heap. You can create your own ObjectInspector implementations, and also add a shark.AppSingletonInspector instance created with the list of internal singletons. |
| [referenceMatchers](reference-matchers.md) | [androidJvm]<br>val [referenceMatchers](reference-matchers.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ReferenceMatcher&gt;<br>Known patterns of references in the heap, added here either to ignore them (IgnoredReferenceMatcher) or to mark them as library leaks (LibraryLeakReferenceMatcher). |
| [stripHeapDump](strip-heap-dump.md) | [androidJvm]<br>val [stripHeapDump](strip-heap-dump.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = false<br>Whether the first step after a heap dump should be to replace the content of all arrays with zeroes. This increases the overall processing time but limits the amount of time the heap dump exists on disk with potential PII. |
