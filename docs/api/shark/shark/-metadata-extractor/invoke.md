[shark](../../index.md) / [shark](../index.md) / [MetadataExtractor](index.md) / [invoke](./invoke.md)

# invoke

`inline operator fun invoke(crossinline block: (HeapGraph) -> `[`Map`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-map/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>): `[`MetadataExtractor`](index.md)

Utility function to create a [MetadataExtractor](index.md) from the passed in [block](invoke.md#shark.MetadataExtractor.Companion$invoke(kotlin.Function1((shark.HeapGraph, kotlin.collections.Map((kotlin.String, )))))/block) lambda instead of
using the anonymous `object : MetadataExtractor` syntax.

Usage:

``` kotlin
val inspector = MetadataExtractor { graph ->

}
```

