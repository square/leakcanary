//[shark-hprof](../../../index.md)/[shark](../index.md)/[StreamingRecordReaderAdapter](index.md)

# StreamingRecordReaderAdapter

[jvm]\
class [StreamingRecordReaderAdapter](index.md)(streamingHprofReader: [StreamingHprofReader](../-streaming-hprof-reader/index.md))

Wraps a [StreamingHprofReader](../-streaming-hprof-reader/index.md) to provide a higher level API that streams [HprofRecord](../-hprof-record/index.md) instances.

## Constructors

| | |
|---|---|
| [StreamingRecordReaderAdapter](-streaming-record-reader-adapter.md) | [jvm]<br>fun [StreamingRecordReaderAdapter](-streaming-record-reader-adapter.md)(streamingHprofReader: [StreamingHprofReader](../-streaming-hprof-reader/index.md)) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [readRecords](read-records.md) | [jvm]<br>fun [readRecords](read-records.md)(recordTypes: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[KClass](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)&lt;out [HprofRecord](../-hprof-record/index.md)&gt;&gt;, listener: [OnHprofRecordListener](../-on-hprof-record-listener/index.md)): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Obtains a new source to read all hprof records from and calls [listener](read-records.md) back for each record that matches one of the provided [recordTypes](read-records.md). |
