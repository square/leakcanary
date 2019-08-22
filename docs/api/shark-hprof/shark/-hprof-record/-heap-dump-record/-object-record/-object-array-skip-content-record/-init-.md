[shark-hprof](../../../../../index.md) / [shark](../../../../index.md) / [HprofRecord](../../../index.md) / [HeapDumpRecord](../../index.md) / [ObjectRecord](../index.md) / [ObjectArraySkipContentRecord](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`ObjectArraySkipContentRecord(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, arrayClassId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, size: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`)`

This isn't a real record type as found in the heap dump. It's an alternative to
[ObjectArrayDumpRecord](../-object-array-dump-record/index.md) for when you don't need the array content.

