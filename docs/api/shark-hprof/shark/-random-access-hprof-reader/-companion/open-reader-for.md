//[shark-hprof](../../../../index.md)/[shark](../../index.md)/[RandomAccessHprofReader](../index.md)/[Companion](index.md)/[openReaderFor](open-reader-for.md)

# openReaderFor

[jvm]\
fun [openReaderFor](open-reader-for.md)(hprofFile: [File](https://docs.oracle.com/javase/8/docs/api/java/io/File.html), hprofHeader: [HprofHeader](../../-hprof-header/index.md) = HprofHeader.parseHeaderOf(hprofFile)): [RandomAccessHprofReader](../index.md)

fun [openReaderFor](open-reader-for.md)(hprofSourceProvider: [RandomAccessSourceProvider](../../-random-access-source-provider/index.md), hprofHeader: [HprofHeader](../../-hprof-header/index.md) = hprofSourceProvider.openRandomAccessSource()
        .use { HprofHeader.parseHeaderOf(it.asStreamingSource()) }): [RandomAccessHprofReader](../index.md)
