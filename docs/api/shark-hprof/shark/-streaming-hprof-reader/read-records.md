[shark-hprof](../../index.md) / [shark](../index.md) / [StreamingHprofReader](index.md) / [readRecords](./read-records.md)

# readRecords

`fun readRecords(recordTags: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`HprofRecordTag`](../-hprof-record-tag/index.md)`>, listener: `[`OnHprofRecordTagListener`](../-on-hprof-record-tag-listener/index.md)`): `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)

Obtains a new source to read all hprof records from and calls [listener](read-records.md#shark.StreamingHprofReader$readRecords(kotlin.collections.Set((shark.HprofRecordTag)), shark.OnHprofRecordTagListener)/listener) back for each record
that matches one of the provided [recordTags](read-records.md#shark.StreamingHprofReader$readRecords(kotlin.collections.Set((shark.HprofRecordTag)), shark.OnHprofRecordTagListener)/recordTags).

**Return**
the number of bytes read from the source

