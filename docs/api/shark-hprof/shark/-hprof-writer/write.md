[shark-hprof](../../index.md) / [shark](../index.md) / [HprofWriter](index.md) / [write](./write.md)

# write

`fun write(record: `[`HprofRecord`](../-hprof-record/index.md)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Appends a [HprofRecord](../-hprof-record/index.md) to the heap dump. If [record](write.md#shark.HprofWriter$write(shark.HprofRecord)/record) is a [HprofRecord.HeapDumpRecord](../-hprof-record/-heap-dump-record/index.md) then
it will not be written to an in memory buffer and written to file only when the next a record
that is not a [HprofRecord.HeapDumpRecord](../-hprof-record/-heap-dump-record/index.md) is written or when [close](close.md) is called.

