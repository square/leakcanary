[shark-hprof](../../index.md) / [shark](../index.md) / [RandomAccessSource](./index.md)

# RandomAccessSource

`interface RandomAccessSource : `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)

### Functions

| Name | Summary |
|---|---|
| [asStreamingSource](as-streaming-source.md) | `open fun asStreamingSource(): BufferedSource` |
| [read](read.md) | `abstract fun read(sink: Buffer, position: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, byteCount: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
