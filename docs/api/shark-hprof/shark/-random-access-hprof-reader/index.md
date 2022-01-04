//[shark-hprof](../../../index.md)/[shark](../index.md)/[RandomAccessHprofReader](index.md)

# RandomAccessHprofReader

[jvm]\
class [RandomAccessHprofReader](index.md) : [Closeable](https://docs.oracle.com/javase/8/docs/api/java/io/Closeable.html)

Reads records in a Hprof source, one at a time with a specific position and size. Call [openReaderFor](-companion/open-reader-for.md) to obtain a new instance.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [close](close.md) | [jvm]<br>open override fun [close](close.md)() |
| [readRecord](read-record.md) | [jvm]<br>fun &lt;[T](read-record.md)&gt; [readRecord](read-record.md)(recordPosition: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), recordSize: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), withRecordReader: [HprofRecordReader](../-hprof-record-reader/index.md).() -&gt; [T](read-record.md)): [T](read-record.md)<br>Loads [recordSize](read-record.md) bytes at [recordPosition](read-record.md) into the buffer that backs [HprofRecordReader](../-hprof-record-reader/index.md) then calls [withRecordReader](read-record.md) with that reader as a receiver. [withRecordReader](read-record.md) is expected to use the receiver reader to read one record of exactly [recordSize](read-record.md) bytes. |
