[shark-hprof](../../index.md) / [shark](../index.md) / [RandomAccessHprofReader](index.md) / [openReaderFor](./open-reader-for.md)

# openReaderFor

`fun openReaderFor(hprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, hprofHeader: `[`HprofHeader`](../-hprof-header/index.md)` = HprofHeader.parseHeaderOf(hprofFile)): `[`RandomAccessHprofReader`](index.md)
`fun openReaderFor(hprofSourceProvider: `[`RandomAccessSourceProvider`](../-random-access-source-provider/index.md)`, hprofHeader: `[`HprofHeader`](../-hprof-header/index.md)` = hprofSourceProvider.openRandomAccessSource()
          .use { HprofHeader.parseHeaderOf(it.asStreamingSource()) }): `[`RandomAccessHprofReader`](index.md)