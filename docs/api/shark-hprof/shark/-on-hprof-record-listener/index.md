//[shark-hprof](../../../index.md)/[shark](../index.md)/[OnHprofRecordListener](index.md)

# OnHprofRecordListener

[jvm]\
fun interface [OnHprofRecordListener](index.md)

Listener passed in to [StreamingHprofReader.readRecords](../-streaming-hprof-reader/read-records.md), gets notified for each [HprofRecord](../-hprof-record/index.md) found in the heap dump which types is in the set of the recordTypes parameter passed to [StreamingHprofReader.readRecords](../-streaming-hprof-reader/read-records.md).

This is a functional interface with which you can create a [OnHprofRecordListener](index.md) from a lambda.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [onHprofRecord](on-hprof-record.md) | [jvm]<br>abstract fun [onHprofRecord](on-hprof-record.md)(position: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), record: [HprofRecord](../-hprof-record/index.md)) |
