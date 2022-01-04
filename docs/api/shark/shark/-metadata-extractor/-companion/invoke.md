//[shark](../../../../index.md)/[shark](../../index.md)/[MetadataExtractor](../index.md)/[Companion](index.md)/[invoke](invoke.md)

# invoke

[jvm]\
inline operator fun [invoke](invoke.md)(crossinline block: (HeapGraph) -&gt; [Map](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-map/index.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt;): [MetadataExtractor](../index.md)

Utility function to create a [MetadataExtractor](../index.md) from the passed in [block](invoke.md) lambda instead of using the anonymous object : MetadataExtractor syntax.

Usage:

val inspector = MetadataExtractor { graph -&gt;\
\
}
