[shark-hprof](../../../../../index.md) / [shark](../../../../index.md) / [HprofRecord](../../../index.md) / [HeapDumpRecord](../../index.md) / [ObjectRecord](../index.md) / [PrimitiveArraySkipContentRecord](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`PrimitiveArraySkipContentRecord(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, size: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, type: `[`PrimitiveType`](../../../../-primitive-type/index.md)`)`

This isn't a real record type as found in the heap dump. It's an alternative to
[PrimitiveArrayDumpRecord](../-primitive-array-dump-record/index.md) for when you don't need the array content.

To limit object allocation while parsing, [HprofReader](../../../../-hprof-reader/index.md) uses a single instance which is
reused after each call to [OnHprofRecordListener.onHprofRecord](../../../../-on-hprof-record-listener/on-hprof-record.md).

