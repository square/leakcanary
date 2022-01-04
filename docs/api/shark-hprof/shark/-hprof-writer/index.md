//[shark-hprof](../../../index.md)/[shark](../index.md)/[HprofWriter](index.md)

# HprofWriter

[jvm]\
class [HprofWriter](index.md) : [Closeable](https://docs.oracle.com/javase/8/docs/api/java/io/Closeable.html)

Generates Hprof files.

Call [openWriterFor](-companion/open-writer-for.md) to obtain a new instance.

Call [write](write.md) to add records and [close](close.md) when you're done.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [close](close.md) | [jvm]<br>open override fun [close](close.md)()<br>Flushes to disk all [HprofRecord.HeapDumpRecord](../-hprof-record/-heap-dump-record/index.md) that are currently written to the in memory buffer, then closes the file. |
| [valuesToBytes](values-to-bytes.md) | [jvm]<br>fun [valuesToBytes](values-to-bytes.md)(values: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[ValueHolder](../-value-holder/index.md)&gt;): [ByteArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte-array/index.html)<br>Helper method for creating a [ByteArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte-array/index.html) for [InstanceDumpRecord.fieldValues](../-hprof-record/-heap-dump-record/-object-record/-instance-dump-record/field-values.md) from a list of [ValueHolder](../-value-holder/index.md). |
| [write](write.md) | [jvm]<br>fun [write](write.md)(record: [HprofRecord](../-hprof-record/index.md))<br>Appends a [HprofRecord](../-hprof-record/index.md) to the heap dump. If [record](write.md) is a [HprofRecord.HeapDumpRecord](../-hprof-record/-heap-dump-record/index.md) then it will not be written to an in memory buffer and written to file only when the next a record that is not a [HprofRecord.HeapDumpRecord](../-hprof-record/-heap-dump-record/index.md) is written or when [close](close.md) is called. |

## Properties

| Name | Summary |
|---|---|
| [hprofHeader](hprof-header.md) | [jvm]<br>val [hprofHeader](hprof-header.md): [HprofHeader](../-hprof-header/index.md) |
