[shark-hprof](../../index.md) / [shark](../index.md) / [StreamingHprofReader](index.md) / [readerFor](./reader-for.md)

# readerFor

`fun readerFor(hprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, hprofHeader: `[`HprofHeader`](../-hprof-header/index.md)` = HprofHeader.parseHeaderOf(hprofFile)): `[`StreamingHprofReader`](index.md)

Creates a [StreamingHprofReader](index.md) for the provided [hprofFile](reader-for.md#shark.StreamingHprofReader.Companion$readerFor(java.io.File, shark.HprofHeader)/hprofFile). [hprofHeader](reader-for.md#shark.StreamingHprofReader.Companion$readerFor(java.io.File, shark.HprofHeader)/hprofHeader) will be read from
[hprofFile](reader-for.md#shark.StreamingHprofReader.Companion$readerFor(java.io.File, shark.HprofHeader)/hprofFile) unless you provide it.

`fun readerFor(hprofSourceProvider: `[`StreamingSourceProvider`](../-streaming-source-provider/index.md)`, hprofHeader: `[`HprofHeader`](../-hprof-header/index.md)` = hprofSourceProvider.openStreamingSource()
          .use { HprofHeader.parseHeaderOf(it) }): `[`StreamingHprofReader`](index.md)

Creates a [StreamingHprofReader](index.md) that will call [StreamingSourceProvider.openStreamingSource](../-streaming-source-provider/open-streaming-source.md)
on every [readRecords](read-records.md) to obtain a [Source](#) to read the hprof data from. Before reading the
hprof records, [StreamingHprofReader](index.md) will skip [HprofHeader.recordsPosition](../-hprof-header/records-position.md) bytes.

