//[shark](../../../index.md)/[shark](../index.md)/[MetadataExtractor](index.md)

# MetadataExtractor

[jvm]\
fun interface [MetadataExtractor](index.md)

Extracts metadata from a hprof to be reported in [HeapAnalysisSuccess.metadata](../-heap-analysis-success/metadata.md).

This is a functional interface with which you can create a [MetadataExtractor](index.md) from a lambda.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [extractMetadata](extract-metadata.md) | [jvm]<br>abstract fun [extractMetadata](extract-metadata.md)(graph: HeapGraph): [Map](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-map/index.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt; |
