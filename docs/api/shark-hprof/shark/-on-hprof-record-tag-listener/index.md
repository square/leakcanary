//[shark-hprof](../../../index.md)/[shark](../index.md)/[OnHprofRecordTagListener](index.md)

# OnHprofRecordTagListener

[jvm]\
fun interface [OnHprofRecordTagListener](index.md)

Listener passed in to [StreamingHprofReader.readRecords](../-streaming-hprof-reader/read-records.md), gets notified for each [HprofRecordTag](../-hprof-record-tag/index.md) found in the heap dump.

Listener implementations are expected to read all bytes corresponding to a given tag from the provided reader before returning.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [onHprofRecord](on-hprof-record.md) | [jvm]<br>abstract fun [onHprofRecord](on-hprof-record.md)(tag: [HprofRecordTag](../-hprof-record-tag/index.md), length: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), reader: [HprofRecordReader](../-hprof-record-reader/index.md)) |
