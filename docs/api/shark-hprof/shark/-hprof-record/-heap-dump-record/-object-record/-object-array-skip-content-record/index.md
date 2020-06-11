[shark-hprof](../../../../../index.md) / [shark](../../../../index.md) / [HprofRecord](../../../index.md) / [HeapDumpRecord](../../index.md) / [ObjectRecord](../index.md) / [ObjectArraySkipContentRecord](./index.md)

# ObjectArraySkipContentRecord

`class ObjectArraySkipContentRecord : `[`HprofRecord.HeapDumpRecord.ObjectRecord`](../index.md)

This isn't a real record type as found in the heap dump. It's an alternative to
[ObjectArrayDumpRecord](../-object-array-dump-record/index.md) for when you don't need the array content.

To limit object allocation while parsing, [HprofReader](../../../../-hprof-reader/index.md) uses a single instance which is
reused after each call to [OnHprofRecordListener.onHprofRecord](../../../../-on-hprof-record-listener/on-hprof-record.md).

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `ObjectArraySkipContentRecord(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, arrayClassId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, size: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`)`<br>This isn't a real record type as found in the heap dump. It's an alternative to [ObjectArrayDumpRecord](../-object-array-dump-record/index.md) for when you don't need the array content. |

### Properties

| Name | Summary |
|---|---|
| [arrayClassId](array-class-id.md) | `var arrayClassId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [id](id.md) | `var id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [size](size.md) | `var size: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [stackTraceSerialNumber](stack-trace-serial-number.md) | `var stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
