[shark-hprof](../../index.md) / [shark](../index.md) / [StreamingRecordReaderAdapter](./index.md)

# StreamingRecordReaderAdapter

`class StreamingRecordReaderAdapter`

Wraps a [StreamingHprofReader](../-streaming-hprof-reader/index.md) to provide a higher level API that streams [HprofRecord](../-hprof-record/index.md)
instances.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `StreamingRecordReaderAdapter(streamingHprofReader: `[`StreamingHprofReader`](../-streaming-hprof-reader/index.md)`)`<br>Wraps a [StreamingHprofReader](../-streaming-hprof-reader/index.md) to provide a higher level API that streams [HprofRecord](../-hprof-record/index.md) instances. |

### Functions

| Name | Summary |
|---|---|
| [readRecords](read-records.md) | `fun readRecords(recordTypes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out `[`HprofRecord`](../-hprof-record/index.md)`>>, listener: `[`OnHprofRecordListener`](../-on-hprof-record-listener/index.md)`): `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Obtains a new source to read all hprof records from and calls [listener](read-records.md#shark.StreamingRecordReaderAdapter$readRecords(kotlin.collections.Set((kotlin.reflect.KClass((shark.HprofRecord)))), shark.OnHprofRecordListener)/listener) back for each record that matches one of the provided [recordTypes](read-records.md#shark.StreamingRecordReaderAdapter$readRecords(kotlin.collections.Set((kotlin.reflect.KClass((shark.HprofRecord)))), shark.OnHprofRecordListener)/recordTypes). |

### Companion Object Functions

| Name | Summary |
|---|---|
| [asHprofTags](as-hprof-tags.md) | `fun `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out `[`HprofRecord`](../-hprof-record/index.md)`>>.asHprofTags(): `[`EnumSet`](https://docs.oracle.com/javase/6/docs/api/java/util/EnumSet.html)`<`[`HprofRecordTag`](../-hprof-record-tag/index.md)`>` |
| [asStreamingRecordReader](as-streaming-record-reader.md) | `fun `[`StreamingHprofReader`](../-streaming-hprof-reader/index.md)`.asStreamingRecordReader(): `[`StreamingRecordReaderAdapter`](./index.md) |
