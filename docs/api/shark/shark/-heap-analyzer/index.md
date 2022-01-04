//[shark](../../../index.md)/[shark](../index.md)/[HeapAnalyzer](index.md)

# HeapAnalyzer

[jvm]\
class [HeapAnalyzer](index.md)(listener: [OnAnalysisProgressListener](../-on-analysis-progress-listener/index.md))

Analyzes heap dumps to look for leaks.

## Constructors

| | |
|---|---|
| [HeapAnalyzer](-heap-analyzer.md) | [jvm]<br>fun [HeapAnalyzer](-heap-analyzer.md)(listener: [OnAnalysisProgressListener](../-on-analysis-progress-listener/index.md)) |

## Functions

| Name | Summary |
|---|---|
| [analyze](analyze.md) | [jvm]<br>fun [analyze](analyze.md)(heapDumpFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), graph: HeapGraph, leakingObjectFinder: [LeakingObjectFinder](../-leaking-object-finder/index.md), referenceMatchers: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[ReferenceMatcher](../-reference-matcher/index.md)&gt; = emptyList(), computeRetainedHeapSize: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = false, objectInspectors: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[ObjectInspector](../-object-inspector/index.md)&gt; = emptyList(), metadataExtractor: [MetadataExtractor](../-metadata-extractor/index.md) = MetadataExtractor.NO_OP): [HeapAnalysis](../-heap-analysis/index.md)<br>Searches the heap dump for leaking instances and then computes the shortest strong reference path from those instances to the GC roots. |
