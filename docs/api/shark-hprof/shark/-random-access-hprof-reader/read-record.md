//[shark-hprof](../../../index.md)/[shark](../index.md)/[RandomAccessHprofReader](index.md)/[readRecord](read-record.md)

# readRecord

[jvm]\
fun &lt;[T](read-record.md)&gt; [readRecord](read-record.md)(recordPosition: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), recordSize: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), withRecordReader: [HprofRecordReader](../-hprof-record-reader/index.md).() -&gt; [T](read-record.md)): [T](read-record.md)

Loads [recordSize](read-record.md) bytes at [recordPosition](read-record.md) into the buffer that backs [HprofRecordReader](../-hprof-record-reader/index.md) then calls [withRecordReader](read-record.md) with that reader as a receiver. [withRecordReader](read-record.md) is expected to use the receiver reader to read one record of exactly [recordSize](read-record.md) bytes.

#### Return

the results from [withRecordReader](read-record.md)
