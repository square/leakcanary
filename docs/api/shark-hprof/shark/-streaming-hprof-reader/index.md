//[shark-hprof](../../../index.md)/[shark](../index.md)/[StreamingHprofReader](index.md)

# StreamingHprofReader

[jvm]\
class [StreamingHprofReader](index.md)

Reads the entire content of a Hprof source in one fell swoop. Call [readerFor](-companion/reader-for.md) to obtain a new instance.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [readRecords](read-records.md) | [jvm]<br>fun [readRecords](read-records.md)(recordTags: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[HprofRecordTag](../-hprof-record-tag/index.md)&gt;, listener: [OnHprofRecordTagListener](../-on-hprof-record-tag-listener/index.md)): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Obtains a new source to read all hprof records from and calls [listener](read-records.md) back for each record that matches one of the provided [recordTags](read-records.md). |

## Extensions

| Name | Summary |
|---|---|
| [asStreamingRecordReader](../-streaming-record-reader-adapter/-companion/as-streaming-record-reader.md) | [jvm]<br>fun [StreamingHprofReader](index.md).[asStreamingRecordReader](../-streaming-record-reader-adapter/-companion/as-streaming-record-reader.md)(): [StreamingRecordReaderAdapter](../-streaming-record-reader-adapter/index.md) |
