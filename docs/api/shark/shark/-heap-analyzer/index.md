[shark](../../index.md) / [shark](../index.md) / [HeapAnalyzer](./index.md)

# HeapAnalyzer

`class HeapAnalyzer`

Analyzes heap dumps to look for leaks.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `HeapAnalyzer(listener: `[`OnAnalysisProgressListener`](../-on-analysis-progress-listener/index.md)`)`<br>Analyzes heap dumps to look for leaks. |

### Functions

| Name | Summary |
|---|---|
| [analyze](analyze.md) | `fun analyze(heapDumpFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, leakingObjectFinder: `[`LeakingObjectFinder`](../-leaking-object-finder/index.md)`, referenceMatchers: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`ReferenceMatcher`](../-reference-matcher/index.md)`> = emptyList(), computeRetainedHeapSize: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = false, objectInspectors: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`ObjectInspector`](../-object-inspector/index.md)`> = emptyList(), metadataExtractor: `[`MetadataExtractor`](../-metadata-extractor/index.md)` = MetadataExtractor.NO_OP, proguardMapping: ProguardMapping? = null): `[`HeapAnalysis`](../-heap-analysis/index.md)<br>Searches the heap dump for leaking instances and then computes the shortest strong reference path from those instances to the GC roots.`fun analyze(heapDumpFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, graph: HeapGraph, leakingObjectFinder: `[`LeakingObjectFinder`](../-leaking-object-finder/index.md)`, referenceMatchers: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`ReferenceMatcher`](../-reference-matcher/index.md)`> = emptyList(), computeRetainedHeapSize: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = false, objectInspectors: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`ObjectInspector`](../-object-inspector/index.md)`> = emptyList(), metadataExtractor: `[`MetadataExtractor`](../-metadata-extractor/index.md)` = MetadataExtractor.NO_OP): `[`HeapAnalysis`](../-heap-analysis/index.md) |
