[shark-hprof](../../index.md) / [shark](../index.md) / [RandomAccessHprofReader](index.md) / [readRecord](./read-record.md)

# readRecord

`fun <T> readRecord(recordPosition: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, recordSize: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, withRecordReader: `[`HprofRecordReader`](../-hprof-record-reader/index.md)`.() -> `[`T`](read-record.md#T)`): `[`T`](read-record.md#T)

Loads [recordSize](read-record.md#shark.RandomAccessHprofReader$readRecord(kotlin.Long, kotlin.Long, kotlin.Function1((shark.HprofRecordReader, shark.RandomAccessHprofReader.readRecord.T)))/recordSize) bytes at [recordPosition](read-record.md#shark.RandomAccessHprofReader$readRecord(kotlin.Long, kotlin.Long, kotlin.Function1((shark.HprofRecordReader, shark.RandomAccessHprofReader.readRecord.T)))/recordPosition) into the buffer that backs [HprofRecordReader](../-hprof-record-reader/index.md)
then calls [withRecordReader](read-record.md#shark.RandomAccessHprofReader$readRecord(kotlin.Long, kotlin.Long, kotlin.Function1((shark.HprofRecordReader, shark.RandomAccessHprofReader.readRecord.T)))/withRecordReader) with that reader as a receiver. [withRecordReader](read-record.md#shark.RandomAccessHprofReader$readRecord(kotlin.Long, kotlin.Long, kotlin.Function1((shark.HprofRecordReader, shark.RandomAccessHprofReader.readRecord.T)))/withRecordReader) is expected
to use the receiver reader to read one record of exactly [recordSize](read-record.md#shark.RandomAccessHprofReader$readRecord(kotlin.Long, kotlin.Long, kotlin.Function1((shark.HprofRecordReader, shark.RandomAccessHprofReader.readRecord.T)))/recordSize) bytes.

**Return**
the results from [withRecordReader](read-record.md#shark.RandomAccessHprofReader$readRecord(kotlin.Long, kotlin.Long, kotlin.Function1((shark.HprofRecordReader, shark.RandomAccessHprofReader.readRecord.T)))/withRecordReader)

