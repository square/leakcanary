//[leakcanary-android-release](../../../index.md)/[leakcanary](../index.md)/[HeapAnalysisConfig](index.md)/[HeapAnalysisConfig](-heap-analysis-config.md)

# HeapAnalysisConfig

[androidJvm]\
fun [HeapAnalysisConfig](-heap-analysis-config.md)(referenceMatchers: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ReferenceMatcher&gt; = AndroidReferenceMatchers.appDefaults, objectInspectors: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;ObjectInspector&gt; = AndroidObjectInspectors.appDefaults, metadataExtractor: MetadataExtractor = AndroidMetadataExtractor, computeRetainedHeapSize: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = true, leakingObjectFinder: LeakingObjectFinder = FilteringLeakingObjectFinder(
    AndroidObjectInspectors.appLeakingObjectFilters
  ), stripHeapDump: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = false)
