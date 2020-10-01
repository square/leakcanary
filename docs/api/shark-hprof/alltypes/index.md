

### All Types

| Name | Summary |
|---|---|
| [shark.ByteArraySourceProvider](../shark/-byte-array-source-provider/index.md) |  |
| [shark.ConstantMemoryMetricsDualSourceProvider](../shark/-constant-memory-metrics-dual-source-provider/index.md) | Captures IO read metrics without using much memory. |
| [shark.DualSourceProvider](../shark/-dual-source-provider.md) | Both a [StreamingSourceProvider](../shark/-streaming-source-provider/index.md) and a [RandomAccessSourceProvider](../shark/-random-access-source-provider/index.md) |
| [shark.FileSourceProvider](../shark/-file-source-provider/index.md) |  |
| [shark.GcRoot](../shark/-gc-root/index.md) | A GcRoot as identified by [HprofRecord.HeapDumpRecord.GcRootRecord](../shark/-hprof-record/-heap-dump-record/-gc-root-record/index.md) in the heap dump. |
| [shark.Hprof](../shark/-hprof/index.md) | Hprof is deprecated, and we offer partial backward compatibility. Any code that was previously using HprofReader directly now has to call [StreamingHprofReader.readerFor](../shark/-streaming-hprof-reader/reader-for.md) or [HprofRandomAcccessReader.readerFor](#) |
| [shark.HprofDeobfuscator](../shark/-hprof-deobfuscator/index.md) | Converts a Hprof file to another file with deobfuscated class and field names. |
| [shark.HprofHeader](../shark/-hprof-header/index.md) | Represents the header metadata of a Hprof file. |
| [shark.HprofPrimitiveArrayStripper](../shark/-hprof-primitive-array-stripper/index.md) | Converts a Hprof file to another file with all primitive arrays replaced with arrays of zeroes, which can be useful to remove PII. Char arrays are handled slightly differently because 0 would be the null character so instead these become arrays of '?'. |
| [shark.HprofReader](../shark/-hprof-reader/index.md) |  |
| [shark.HprofRecord](../shark/-hprof-record/index.md) | A Hprof record. These data structure map 1:1 with how records are written in hprof files. |
| [shark.HprofRecordReader](../shark/-hprof-record-reader/index.md) | Reads hprof content from an Okio [BufferedSource](#). |
| [shark.HprofRecordTag](../shark/-hprof-record-tag/index.md) |  |
| [shark.HprofVersion](../shark/-hprof-version/index.md) | Supported hprof versions |
| [shark.HprofWriter](../shark/-hprof-writer/index.md) | Generates Hprof files. |
| [shark.OnHprofRecordListener](../shark/-on-hprof-record-listener/index.md) | Listener passed in to [StreamingHprofReader.readRecords](../shark/-streaming-hprof-reader/read-records.md), gets notified for each [HprofRecord](../shark/-hprof-record/index.md) found in the heap dump which types is in the set of the recordTypes parameter passed to [StreamingHprofReader.readRecords](../shark/-streaming-hprof-reader/read-records.md). |
| [shark.OnHprofRecordTagListener](../shark/-on-hprof-record-tag-listener/index.md) | Listener passed in to [StreamingHprofReader.readRecords](../shark/-streaming-hprof-reader/read-records.md), gets notified for each [HprofRecordTag](../shark/-hprof-record-tag/index.md) found in the heap dump. |
| [shark.PrimitiveType](../shark/-primitive-type/index.md) | A primitive type in the prof. |
| [shark.ProguardMapping](../shark/-proguard-mapping/index.md) |  |
| [shark.ProguardMappingReader](../shark/-proguard-mapping-reader/index.md) |  |
| [shark.RandomAccessHprofReader](../shark/-random-access-hprof-reader/index.md) | Reads records in a Hprof source, one at a time with a specific position and size. Call [openReaderFor](../shark/-random-access-hprof-reader/open-reader-for.md) to obtain a new instance. |
| [shark.RandomAccessSource](../shark/-random-access-source/index.md) |  |
| [shark.RandomAccessSourceProvider](../shark/-random-access-source-provider/index.md) | Can open [RandomAccessSource](../shark/-random-access-source/index.md) instances. |
| [shark.StreamingHprofReader](../shark/-streaming-hprof-reader/index.md) | Reads the entire content of a Hprof source in one fell swoop. Call [readerFor](../shark/-streaming-hprof-reader/reader-for.md) to obtain a new instance. |
| [shark.StreamingRecordReaderAdapter](../shark/-streaming-record-reader-adapter/index.md) | Wraps a [StreamingHprofReader](../shark/-streaming-hprof-reader/index.md) to provide a higher level API that streams [HprofRecord](../shark/-hprof-record/index.md) instances. |
| [shark.StreamingSourceProvider](../shark/-streaming-source-provider/index.md) | Can open [Source](#) instances. |
| [shark.ValueHolder](../shark/-value-holder/index.md) | A value in the heap dump, which can be a [ReferenceHolder](../shark/-value-holder/-reference-holder/index.md) or a primitive type. |
