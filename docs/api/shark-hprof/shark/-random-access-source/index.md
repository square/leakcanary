//[shark-hprof](../../../index.md)/[shark](../index.md)/[RandomAccessSource](index.md)

# RandomAccessSource

[jvm]\
interface [RandomAccessSource](index.md) : [Closeable](https://docs.oracle.com/javase/8/docs/api/java/io/Closeable.html)

## Functions

| Name | Summary |
|---|---|
| [asStreamingSource](as-streaming-source.md) | [jvm]<br>open fun [asStreamingSource](as-streaming-source.md)(): [BufferedSource](https://square.github.io/okio/2.x/okio/okio/-buffered-source/index.html) |
| [close](index.md#358956095%2FFunctions%2F219937657) | [jvm]<br>abstract override fun [close](index.md#358956095%2FFunctions%2F219937657)() |
| [read](read.md) | [jvm]<br>abstract fun [read](read.md)(sink: [Buffer](https://square.github.io/okio/2.x/okio/okio/-buffer/index.html), position: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), byteCount: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
