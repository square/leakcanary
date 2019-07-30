[shark-hprof](../../index.md) / [shark](../index.md) / [HprofReader](./index.md)

# HprofReader

`class HprofReader`

Reads hprof content from an Okio [BufferedSource](#).

Not thread safe, should be used from a single thread.

Binary Dump Format reference: http://hg.openjdk.java.net/jdk6/jdk6/jdk/raw-file/tip/src/share/demo/jvmti/hprof/manual.html#mozTocId848088

The Android Hprof format differs in some ways from that reference. This parser implementation
is largely adapted from https://android.googlesource.com/platform/tools/base/+/studio-master-dev/perflib/src/main/java/com/android/tools/perflib

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `HprofReader(source: BufferedSource, identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, startByteReadCount: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = 0L)`<br>Reads hprof content from an Okio [BufferedSource](#). |

### Properties

| Name | Summary |
|---|---|
| [byteReadCount](byte-read-count.md) | `var byteReadCount: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Starts at [startByteReadCount](start-byte-read-count.md) and increases as [HprofReader](./index.md) reads bytes. This is useful for tracking the position of content in the backing [source](#). This never resets. |
| [identifierByteSize](identifier-byte-size.md) | `val identifierByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>Size of Hprof identifiers. Identifiers are used to represent UTF8 strings, objects, stack traces, etc. They can have the same size as host pointers or sizeof(void*), but are not required to be. |
| [startByteReadCount](start-byte-read-count.md) | `val startByteReadCount: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>How many bytes have already been read from [source](#) when this [HprofReader](./index.md) is created. |

### Functions

| Name | Summary |
|---|---|
| [readClassDumpRecord](read-class-dump-record.md) | `fun readClassDumpRecord(): `[`HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord`](../-hprof-record/-heap-dump-record/-object-record/-class-dump-record/index.md)<br>Reads a full class record after a class dump tag. |
| [readHprofRecords](read-hprof-records.md) | `fun readHprofRecords(recordTypes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out `[`HprofRecord`](../-hprof-record/index.md)`>>, listener: `[`OnHprofRecordListener`](../-on-hprof-record-listener/index.md)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Reads all hprof records from [source](#). Assumes the [reader](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io/java.io.-file/reader.html) was just created, with a source that currently points to the start position of hprof records. |
| [readInstanceDumpRecord](read-instance-dump-record.md) | `fun readInstanceDumpRecord(): `[`HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord`](../-hprof-record/-heap-dump-record/-object-record/-instance-dump-record/index.md)<br>Reads a full instance record after a instance dump tag. |
| [readObjectArrayDumpRecord](read-object-array-dump-record.md) | `fun readObjectArrayDumpRecord(): `[`HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord`](../-hprof-record/-heap-dump-record/-object-record/-object-array-dump-record/index.md)<br>Reads a full object array record after a object array dump tag. |
| [readPrimitiveArrayDumpRecord](read-primitive-array-dump-record.md) | `fun readPrimitiveArrayDumpRecord(): `[`HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord`](../-hprof-record/-heap-dump-record/-object-record/-primitive-array-dump-record/index.md)<br>Reads a full primitive array record after a primitive array dump tag. |
| [readValue](read-value.md) | `fun readValue(type: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`ValueHolder`](../-value-holder/index.md)<br>Reads a value in the heap dump, which can be a reference or a primitive type. |
