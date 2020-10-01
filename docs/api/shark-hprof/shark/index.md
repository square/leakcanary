[shark-hprof](../index.md) / [shark](./index.md)

## Package shark

### Types

| Name | Summary |
|---|---|
| [ByteArraySourceProvider](-byte-array-source-provider/index.md) | `class ByteArraySourceProvider : `[`DualSourceProvider`](-dual-source-provider.md) |
| [ConstantMemoryMetricsDualSourceProvider](-constant-memory-metrics-dual-source-provider/index.md) | `class ConstantMemoryMetricsDualSourceProvider : `[`DualSourceProvider`](-dual-source-provider.md)<br>Captures IO read metrics without using much memory. |
| [DualSourceProvider](-dual-source-provider.md) | `interface DualSourceProvider : `[`StreamingSourceProvider`](-streaming-source-provider/index.md)`, `[`RandomAccessSourceProvider`](-random-access-source-provider/index.md)<br>Both a [StreamingSourceProvider](-streaming-source-provider/index.md) and a [RandomAccessSourceProvider](-random-access-source-provider/index.md) |
| [FileSourceProvider](-file-source-provider/index.md) | `class FileSourceProvider : `[`DualSourceProvider`](-dual-source-provider.md) |
| [GcRoot](-gc-root/index.md) | `sealed class GcRoot`<br>A GcRoot as identified by [HprofRecord.HeapDumpRecord.GcRootRecord](-hprof-record/-heap-dump-record/-gc-root-record/index.md) in the heap dump. |
| [Hprof](-hprof/index.md) | `class ~~Hprof~~ : `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)<br>Hprof is deprecated, and we offer partial backward compatibility. Any code that was previously using HprofReader directly now has to call [StreamingHprofReader.readerFor](-streaming-hprof-reader/reader-for.md) or [HprofRandomAcccessReader.readerFor](#) |
| [HprofDeobfuscator](-hprof-deobfuscator/index.md) | `class HprofDeobfuscator`<br>Converts a Hprof file to another file with deobfuscated class and field names. |
| [HprofHeader](-hprof-header/index.md) | `data class HprofHeader`<br>Represents the header metadata of a Hprof file. |
| [HprofPrimitiveArrayStripper](-hprof-primitive-array-stripper/index.md) | `class HprofPrimitiveArrayStripper`<br>Converts a Hprof file to another file with all primitive arrays replaced with arrays of zeroes, which can be useful to remove PII. Char arrays are handled slightly differently because 0 would be the null character so instead these become arrays of '?'. |
| [HprofReader](-hprof-reader/index.md) | `class ~~HprofReader~~` |
| [HprofRecord](-hprof-record/index.md) | `sealed class HprofRecord`<br>A Hprof record. These data structure map 1:1 with how records are written in hprof files. |
| [HprofRecordReader](-hprof-record-reader/index.md) | `class HprofRecordReader`<br>Reads hprof content from an Okio [BufferedSource](#). |
| [HprofRecordTag](-hprof-record-tag/index.md) | `enum class HprofRecordTag` |
| [HprofVersion](-hprof-version/index.md) | `enum class HprofVersion`<br>Supported hprof versions |
| [HprofWriter](-hprof-writer/index.md) | `class HprofWriter : `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)<br>Generates Hprof files. |
| [OnHprofRecordListener](-on-hprof-record-listener/index.md) | `interface OnHprofRecordListener`<br>Listener passed in to [StreamingHprofReader.readRecords](-streaming-hprof-reader/read-records.md), gets notified for each [HprofRecord](-hprof-record/index.md) found in the heap dump which types is in the set of the recordTypes parameter passed to [StreamingHprofReader.readRecords](-streaming-hprof-reader/read-records.md). |
| [OnHprofRecordTagListener](-on-hprof-record-tag-listener/index.md) | `interface OnHprofRecordTagListener`<br>Listener passed in to [StreamingHprofReader.readRecords](-streaming-hprof-reader/read-records.md), gets notified for each [HprofRecordTag](-hprof-record-tag/index.md) found in the heap dump. |
| [PrimitiveType](-primitive-type/index.md) | `enum class PrimitiveType`<br>A primitive type in the prof. |
| [ProguardMapping](-proguard-mapping/index.md) | `class ProguardMapping` |
| [ProguardMappingReader](-proguard-mapping-reader/index.md) | `class ProguardMappingReader` |
| [RandomAccessHprofReader](-random-access-hprof-reader/index.md) | `class RandomAccessHprofReader : `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)<br>Reads records in a Hprof source, one at a time with a specific position and size. Call [openReaderFor](-random-access-hprof-reader/open-reader-for.md) to obtain a new instance. |
| [RandomAccessSource](-random-access-source/index.md) | `interface RandomAccessSource : `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html) |
| [RandomAccessSourceProvider](-random-access-source-provider/index.md) | `interface RandomAccessSourceProvider`<br>Can open [RandomAccessSource](-random-access-source/index.md) instances. |
| [StreamingHprofReader](-streaming-hprof-reader/index.md) | `class StreamingHprofReader`<br>Reads the entire content of a Hprof source in one fell swoop. Call [readerFor](-streaming-hprof-reader/reader-for.md) to obtain a new instance. |
| [StreamingRecordReaderAdapter](-streaming-record-reader-adapter/index.md) | `class StreamingRecordReaderAdapter`<br>Wraps a [StreamingHprofReader](-streaming-hprof-reader/index.md) to provide a higher level API that streams [HprofRecord](-hprof-record/index.md) instances. |
| [StreamingSourceProvider](-streaming-source-provider/index.md) | `interface StreamingSourceProvider`<br>Can open [Source](#) instances. |
| [ValueHolder](-value-holder/index.md) | `sealed class ValueHolder`<br>A value in the heap dump, which can be a [ReferenceHolder](-value-holder/-reference-holder/index.md) or a primitive type. |
