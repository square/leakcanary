[shark](../../index.md) / [shark](../index.md) / [MetadataExtractor](./index.md)

# MetadataExtractor

`interface MetadataExtractor`

Extracts metadata from a hprof to be reported in [HeapAnalysisSuccess.metadata](../-heap-analysis-success/metadata.md).

You can create a [MetadataExtractor](./index.md) from a lambda by calling [invoke](invoke.md).

### Functions

| Name | Summary |
|---|---|
| [extractMetadata](extract-metadata.md) | `abstract fun extractMetadata(graph: HeapGraph): `[`Map`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-map/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>` |

### Companion Object Properties

| Name | Summary |
|---|---|
| [NO_OP](-n-o_-o-p.md) | `val NO_OP: `[`MetadataExtractor`](./index.md)<br>A no-op [MetadataExtractor](./index.md) |

### Companion Object Functions

| Name | Summary |
|---|---|
| [invoke](invoke.md) | `operator fun invoke(block: (HeapGraph) -> `[`Map`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-map/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>): `[`MetadataExtractor`](./index.md)<br>Utility function to create a [MetadataExtractor](./index.md) from the passed in [block](invoke.md#shark.MetadataExtractor.Companion$invoke(kotlin.Function1((shark.HeapGraph, kotlin.collections.Map((kotlin.String, )))))/block) lambda instead of using the anonymous `object : MetadataExtractor` syntax. |
