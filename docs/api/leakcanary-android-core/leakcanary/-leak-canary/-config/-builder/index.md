[leakcanary-android-core](../../../../index.md) / [leakcanary](../../../index.md) / [LeakCanary](../../index.md) / [Config](../index.md) / [Builder](./index.md)

# Builder

`class Builder`

Builder for [LeakCanary.Config](../index.md) intended to be used only from Java code.

Usage:

```
LeakCanary.Config config = LeakCanary.getConfig().newBuilder()
   .retainedVisibleThreshold(3)
   .build();
LeakCanary.setConfig(config);
```

For idiomatic Kotlin use `copy()` method instead:

```
LeakCanary.config = LeakCanary.config.copy(retainedVisibleThreshold = 3)
```

### Functions

| Name | Summary |
|---|---|
| [build](build.md) | `fun build(): `[`LeakCanary.Config`](../index.md) |
| [computeRetainedHeapSize](compute-retained-heap-size.md) | `fun computeRetainedHeapSize(computeRetainedHeapSize: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`): `[`LeakCanary.Config.Builder`](./index.md) |
| [dumpHeap](dump-heap.md) | `fun dumpHeap(dumpHeap: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`): `[`LeakCanary.Config.Builder`](./index.md) |
| [dumpHeapWhenDebugging](dump-heap-when-debugging.md) | `fun dumpHeapWhenDebugging(dumpHeapWhenDebugging: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`): `[`LeakCanary.Config.Builder`](./index.md) |
| [leakingObjectFinder](leaking-object-finder.md) | `fun leakingObjectFinder(leakingObjectFinder: LeakingObjectFinder): `[`LeakCanary.Config.Builder`](./index.md) |
| [maxStoredHeapDumps](max-stored-heap-dumps.md) | `fun maxStoredHeapDumps(maxStoredHeapDumps: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`LeakCanary.Config.Builder`](./index.md) |
| [metadataExtractor](metadata-extractor.md) | `fun metadataExtractor(metadataExtractor: MetadataExtractor): `[`LeakCanary.Config.Builder`](./index.md) |
| [objectInspectors](object-inspectors.md) | `fun objectInspectors(objectInspectors: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ObjectInspector>): `[`LeakCanary.Config.Builder`](./index.md) |
| [onHeapAnalyzedListener](on-heap-analyzed-listener.md) | `fun onHeapAnalyzedListener(onHeapAnalyzedListener: `[`OnHeapAnalyzedListener`](../../../-on-heap-analyzed-listener/index.md)`): `[`LeakCanary.Config.Builder`](./index.md) |
| [referenceMatchers](reference-matchers.md) | `fun referenceMatchers(referenceMatchers: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ReferenceMatcher>): `[`LeakCanary.Config.Builder`](./index.md) |
| [requestWriteExternalStoragePermission](request-write-external-storage-permission.md) | `fun requestWriteExternalStoragePermission(requestWriteExternalStoragePermission: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`): `[`LeakCanary.Config.Builder`](./index.md) |
| [retainedVisibleThreshold](retained-visible-threshold.md) | `fun retainedVisibleThreshold(retainedVisibleThreshold: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`LeakCanary.Config.Builder`](./index.md) |
