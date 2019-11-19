[leakcanary-android-core](../../../index.md) / [leakcanary](../../index.md) / [LeakCanary](../index.md) / [Config](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`Config(dumpHeap: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, dumpHeapWhenDebugging: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = false, retainedVisibleThreshold: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)` = 5, referenceMatchers: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ReferenceMatcher> = AndroidReferenceMatchers.appDefaults, objectInspectors: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ObjectInspector> = AndroidObjectInspectors.appDefaults, onHeapAnalyzedListener: `[`OnHeapAnalyzedListener`](../../-on-heap-analyzed-listener/index.md)` = DefaultOnHeapAnalyzedListener.create(), metatadaExtractor: MetadataExtractor = AndroidMetadataExtractor, computeRetainedHeapSize: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, maxStoredHeapDumps: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)` = 7, requestWriteExternalStoragePermission: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = false, useExperimentalLeakFinders: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = false)`

LeakCanary configuration data class. Properties can be updated via [copy](#).

**See Also**

[config](../config.md)

