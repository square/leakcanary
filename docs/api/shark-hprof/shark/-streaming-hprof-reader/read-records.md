//[shark-hprof](../../../index.md)/[shark](../index.md)/[StreamingHprofReader](index.md)/[readRecords](read-records.md)

# readRecords

[jvm]\
fun [readRecords](read-records.md)(recordTags: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[HprofRecordTag](../-hprof-record-tag/index.md)&gt;, listener: [OnHprofRecordTagListener](../-on-hprof-record-tag-listener/index.md)): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)

Obtains a new source to read all hprof records from and calls [listener](read-records.md) back for each record that matches one of the provided [recordTags](read-records.md).

#### Return

the number of bytes read from the source
