//[shark-hprof](../../../index.md)/[shark](../index.md)/[StreamingRecordReaderAdapter](index.md)/[readRecords](read-records.md)

# readRecords

[jvm]\
fun [readRecords](read-records.md)(recordTypes: [Set](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)&lt;[KClass](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)&lt;out [HprofRecord](../-hprof-record/index.md)&gt;&gt;, listener: [OnHprofRecordListener](../-on-hprof-record-listener/index.md)): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)

Obtains a new source to read all hprof records from and calls [listener](read-records.md) back for each record that matches one of the provided [recordTypes](read-records.md).

#### Return

the number of bytes read from the source
