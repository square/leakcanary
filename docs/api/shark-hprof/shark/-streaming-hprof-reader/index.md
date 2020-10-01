[shark-hprof](../../index.md) / [shark](../index.md) / [StreamingHprofReader](./index.md)

# StreamingHprofReader

`class StreamingHprofReader`

Reads the entire content of a Hprof source in one fell swoop.
Call [readerFor](reader-for.md) to obtain a new instance.

### Functions

| Name | Summary |
|---|---|
| [readRecords](read-records.md) | `fun readRecords(recordTags: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`HprofRecordTag`](../-hprof-record-tag/index.md)`>, listener: `[`OnHprofRecordTagListener`](../-on-hprof-record-tag-listener/index.md)`): `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Obtains a new source to read all hprof records from and calls [listener](read-records.md#shark.StreamingHprofReader$readRecords(kotlin.collections.Set((shark.HprofRecordTag)), shark.OnHprofRecordTagListener)/listener) back for each record that matches one of the provided [recordTags](read-records.md#shark.StreamingHprofReader$readRecords(kotlin.collections.Set((shark.HprofRecordTag)), shark.OnHprofRecordTagListener)/recordTags). |

### Companion Object Functions

| Name | Summary |
|---|---|
| [readerFor](reader-for.md) | `fun readerFor(hprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, hprofHeader: `[`HprofHeader`](../-hprof-header/index.md)` = HprofHeader.parseHeaderOf(hprofFile)): `[`StreamingHprofReader`](./index.md)<br>Creates a [StreamingHprofReader](./index.md) for the provided [hprofFile](reader-for.md#shark.StreamingHprofReader.Companion$readerFor(java.io.File, shark.HprofHeader)/hprofFile). [hprofHeader](reader-for.md#shark.StreamingHprofReader.Companion$readerFor(java.io.File, shark.HprofHeader)/hprofHeader) will be read from [hprofFile](reader-for.md#shark.StreamingHprofReader.Companion$readerFor(java.io.File, shark.HprofHeader)/hprofFile) unless you provide it.`fun readerFor(hprofSourceProvider: `[`StreamingSourceProvider`](../-streaming-source-provider/index.md)`, hprofHeader: `[`HprofHeader`](../-hprof-header/index.md)` = hprofSourceProvider.openStreamingSource()
          .use { HprofHeader.parseHeaderOf(it) }): `[`StreamingHprofReader`](./index.md)<br>Creates a [StreamingHprofReader](./index.md) that will call [StreamingSourceProvider.openStreamingSource](../-streaming-source-provider/open-streaming-source.md) on every [readRecords](read-records.md) to obtain a [Source](#) to read the hprof data from. Before reading the hprof records, [StreamingHprofReader](./index.md) will skip [HprofHeader.recordsPosition](../-hprof-header/records-position.md) bytes. |
