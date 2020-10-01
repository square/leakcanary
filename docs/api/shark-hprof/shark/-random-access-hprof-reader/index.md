[shark-hprof](../../index.md) / [shark](../index.md) / [RandomAccessHprofReader](./index.md)

# RandomAccessHprofReader

`class RandomAccessHprofReader : `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)

Reads records in a Hprof source, one at a time with a specific position and size.
Call [openReaderFor](open-reader-for.md) to obtain a new instance.

### Functions

| Name | Summary |
|---|---|
| [close](close.md) | `fun close(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [readRecord](read-record.md) | `fun <T> readRecord(recordPosition: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, recordSize: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, withRecordReader: `[`HprofRecordReader`](../-hprof-record-reader/index.md)`.() -> `[`T`](read-record.md#T)`): `[`T`](read-record.md#T)<br>Loads [recordSize](read-record.md#shark.RandomAccessHprofReader$readRecord(kotlin.Long, kotlin.Long, kotlin.Function1((shark.HprofRecordReader, shark.RandomAccessHprofReader.readRecord.T)))/recordSize) bytes at [recordPosition](read-record.md#shark.RandomAccessHprofReader$readRecord(kotlin.Long, kotlin.Long, kotlin.Function1((shark.HprofRecordReader, shark.RandomAccessHprofReader.readRecord.T)))/recordPosition) into the buffer that backs [HprofRecordReader](../-hprof-record-reader/index.md) then calls [withRecordReader](read-record.md#shark.RandomAccessHprofReader$readRecord(kotlin.Long, kotlin.Long, kotlin.Function1((shark.HprofRecordReader, shark.RandomAccessHprofReader.readRecord.T)))/withRecordReader) with that reader as a receiver. [withRecordReader](read-record.md#shark.RandomAccessHprofReader$readRecord(kotlin.Long, kotlin.Long, kotlin.Function1((shark.HprofRecordReader, shark.RandomAccessHprofReader.readRecord.T)))/withRecordReader) is expected to use the receiver reader to read one record of exactly [recordSize](read-record.md#shark.RandomAccessHprofReader$readRecord(kotlin.Long, kotlin.Long, kotlin.Function1((shark.HprofRecordReader, shark.RandomAccessHprofReader.readRecord.T)))/recordSize) bytes. |

### Companion Object Functions

| Name | Summary |
|---|---|
| [openReaderFor](open-reader-for.md) | `fun openReaderFor(hprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, hprofHeader: `[`HprofHeader`](../-hprof-header/index.md)` = HprofHeader.parseHeaderOf(hprofFile)): `[`RandomAccessHprofReader`](./index.md)<br>`fun openReaderFor(hprofSourceProvider: `[`RandomAccessSourceProvider`](../-random-access-source-provider/index.md)`, hprofHeader: `[`HprofHeader`](../-hprof-header/index.md)` = hprofSourceProvider.openRandomAccessSource()
          .use { HprofHeader.parseHeaderOf(it.asStreamingSource()) }): `[`RandomAccessHprofReader`](./index.md) |
