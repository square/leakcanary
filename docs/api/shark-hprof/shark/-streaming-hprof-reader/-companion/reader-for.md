//[shark-hprof](../../../../index.md)/[shark](../../index.md)/[StreamingHprofReader](../index.md)/[Companion](index.md)/[readerFor](reader-for.md)

# readerFor

[jvm]\
fun [readerFor](reader-for.md)(hprofFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), hprofHeader: [HprofHeader](../../-hprof-header/index.md) = HprofHeader.parseHeaderOf(hprofFile)): [StreamingHprofReader](../index.md)

Creates a [StreamingHprofReader](../index.md) for the provided [hprofFile](reader-for.md). [hprofHeader](reader-for.md) will be read from [hprofFile](reader-for.md) unless you provide it.

[jvm]\
fun [readerFor](reader-for.md)(hprofSourceProvider: [StreamingSourceProvider](../../-streaming-source-provider/index.md), hprofHeader: [HprofHeader](../../-hprof-header/index.md) = hprofSourceProvider.openStreamingSource()
        .use { HprofHeader.parseHeaderOf(it) }): [StreamingHprofReader](../index.md)

Creates a [StreamingHprofReader](../index.md) that will call [StreamingSourceProvider.openStreamingSource](../../-streaming-source-provider/open-streaming-source.md) on every [readRecords](../read-records.md) to obtain a [Source](https://square.github.io/okio/2.x/okio/okio/-source/index.html) to read the hprof data from. Before reading the hprof records, [StreamingHprofReader](../index.md) will skip [HprofHeader.recordsPosition](../../-hprof-header/records-position.md) bytes.
