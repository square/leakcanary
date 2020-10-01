[shark-hprof](../../index.md) / [shark](../index.md) / [StreamingRecordReaderAdapter](index.md) / [readRecords](./read-records.md)

# readRecords

`fun readRecords(recordTypes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out `[`HprofRecord`](../-hprof-record/index.md)`>>, listener: `[`OnHprofRecordListener`](../-on-hprof-record-listener/index.md)`): `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)

Obtains a new source to read all hprof records from and calls [listener](read-records.md#shark.StreamingRecordReaderAdapter$readRecords(kotlin.collections.Set((kotlin.reflect.KClass((shark.HprofRecord)))), shark.OnHprofRecordListener)/listener) back for each record
that matches one of the provided [recordTypes](read-records.md#shark.StreamingRecordReaderAdapter$readRecords(kotlin.collections.Set((kotlin.reflect.KClass((shark.HprofRecord)))), shark.OnHprofRecordListener)/recordTypes).

**Return**
the number of bytes read from the source

