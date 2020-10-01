[shark-hprof](../../index.md) / [shark](../index.md) / [HprofWriter](./index.md)

# HprofWriter

`class HprofWriter : `[`Closeable`](https://docs.oracle.com/javase/6/docs/api/java/io/Closeable.html)

Generates Hprof files.

Call [openWriterFor](open-writer-for.md) to obtain a new instance.

Call [write](write.md) to add records and [close](close.md) when you're done.

### Properties

| Name | Summary |
|---|---|
| [hprofHeader](hprof-header.md) | `val hprofHeader: `[`HprofHeader`](../-hprof-header/index.md) |
| [hprofVersion](hprof-version.md) | `val ~~hprofVersion~~: `[`Hprof.HprofVersion`](../-hprof/-hprof-version/index.md) |
| [identifierByteSize](identifier-byte-size.md) | `val ~~identifierByteSize~~: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |

### Functions

| Name | Summary |
|---|---|
| [close](close.md) | `fun close(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Flushes to disk all [HprofRecord.HeapDumpRecord](../-hprof-record/-heap-dump-record/index.md) that are currently written to the in memory buffer, then closes the file. |
| [valuesToBytes](values-to-bytes.md) | `fun valuesToBytes(values: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`ValueHolder`](../-value-holder/index.md)`>): `[`ByteArray`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte-array/index.html)<br>Helper method for creating a [ByteArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte-array/index.html) for [InstanceDumpRecord.fieldValues](../-hprof-record/-heap-dump-record/-object-record/-instance-dump-record/field-values.md) from a list of [ValueHolder](../-value-holder/index.md). |
| [write](write.md) | `fun write(record: `[`HprofRecord`](../-hprof-record/index.md)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Appends a [HprofRecord](../-hprof-record/index.md) to the heap dump. If [record](write.md#shark.HprofWriter$write(shark.HprofRecord)/record) is a [HprofRecord.HeapDumpRecord](../-hprof-record/-heap-dump-record/index.md) then it will not be written to an in memory buffer and written to file only when the next a record that is not a [HprofRecord.HeapDumpRecord](../-hprof-record/-heap-dump-record/index.md) is written or when [close](close.md) is called. |

### Companion Object Functions

| Name | Summary |
|---|---|
| [open](open.md) | `fun ~~open~~(hprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)` = 4, hprofVersion: `[`Hprof.HprofVersion`](../-hprof/-hprof-version/index.md)` = Hprof.HprofVersion.ANDROID): `[`HprofWriter`](./index.md) |
| [openWriterFor](open-writer-for.md) | `fun openWriterFor(hprofFile: `[`File`](https://docs.oracle.com/javase/6/docs/api/java/io/File.html)`, hprofHeader: `[`HprofHeader`](../-hprof-header/index.md)` = HprofHeader()): `[`HprofWriter`](./index.md)<br>`fun openWriterFor(hprofSink: BufferedSink, hprofHeader: `[`HprofHeader`](../-hprof-header/index.md)` = HprofHeader()): `[`HprofWriter`](./index.md) |
