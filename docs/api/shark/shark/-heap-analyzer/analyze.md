[shark](../../index.md) / [shark](../index.md) / [HeapAnalyzer](index.md) / [analyze](./analyze.md)

# analyze

`fun analyze(heapDumpFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, referenceMatchers: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`ReferenceMatcher`](../-reference-matcher/index.md)`> = emptyList(), computeRetainedHeapSize: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = false, objectInspectors: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`ObjectInspector`](../-object-inspector/index.md)`> = emptyList(), leakFinders: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`ObjectInspector`](../-object-inspector/index.md)`> = objectInspectors, metadataExtractor: `[`MetadataExtractor`](../-metadata-extractor/index.md)` = MetadataExtractor.NO_OP, proguardMapping: ProguardMapping? = null): `[`HeapAnalysis`](../-heap-analysis/index.md)

Searches the heap dump for leaking instances and then computes the shortest strong reference
path from those instances to the GC roots.

