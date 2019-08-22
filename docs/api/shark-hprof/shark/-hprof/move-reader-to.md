[shark-hprof](../../index.md) / [shark](../index.md) / [Hprof](index.md) / [moveReaderTo](./move-reader-to.md)

# moveReaderTo

`fun moveReaderTo(newPosition: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Moves [reader](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io/java.io.-file/reader.html) to a new position in the hprof file. This is transparent to the reader, and
will not reset [HprofReader.position](../-hprof-reader/position.md).

