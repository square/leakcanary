[shark-hprof](../index.md) / [shark](./index.md)

## Package shark

### Types

| Name | Summary |
|---|---|
| [GcRoot](-gc-root/index.md) | `sealed class GcRoot`<br>A GcRoot as identified by [HprofRecord.HeapDumpRecord.GcRootRecord](-hprof-record/-heap-dump-record/-gc-root-record/index.md) in the heap dump. |
| [Hprof](-hprof/index.md) | `class Hprof : `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)<br>An opened Hprof file which can be read via [reader](-hprof/reader.md). Open a new hprof with [open](-hprof/open.md), and don't forget to call [close](-hprof/close.md) once done. |
| [HprofPrimitiveArrayStripper](-hprof-primitive-array-stripper/index.md) | `class HprofPrimitiveArrayStripper`<br>Converts a Hprof file to another file with all primitive arrays replaced with arrays of zeroes, which can be useful to remove PII. Char arrays are handled slightly differently because 0 would be the null character so instead these become arrays of '?'. |
| [HprofReader](-hprof-reader/index.md) | `class HprofReader`<br>Reads hprof content from an Okio [BufferedSource](#). |
| [HprofRecord](-hprof-record/index.md) | `sealed class HprofRecord`<br>A Hprof record. These data structure map 1:1 with how records are written in hprof files. |
| [HprofWriter](-hprof-writer/index.md) | `class HprofWriter : `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)<br>Generates Hprof files. |
| [OnHprofRecordListener](-on-hprof-record-listener/index.md) | `interface OnHprofRecordListener`<br>Listener passed in to [HprofReader.readHprofRecords](-hprof-reader/read-hprof-records.md), gets notified for each [HprofRecord](-hprof-record/index.md) found in the heap dump which types is in the set of the recordTypes parameter passed to [HprofReader.readHprofRecords](-hprof-reader/read-hprof-records.md). |
| [PrimitiveType](-primitive-type/index.md) | `enum class PrimitiveType`<br>A primitive type in the prof. |
| [ValueHolder](-value-holder/index.md) | `sealed class ValueHolder`<br>A value in the heap dump, which can be a [ReferenceHolder](-value-holder/-reference-holder/index.md) or a primitive type. |
