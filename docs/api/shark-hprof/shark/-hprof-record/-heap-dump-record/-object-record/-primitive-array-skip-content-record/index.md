[shark-hprof](../../../../../index.md) / [shark](../../../../index.md) / [HprofRecord](../../../index.md) / [HeapDumpRecord](../../index.md) / [ObjectRecord](../index.md) / [PrimitiveArraySkipContentRecord](./index.md)

# PrimitiveArraySkipContentRecord

`class PrimitiveArraySkipContentRecord : `[`HprofRecord.HeapDumpRecord.ObjectRecord`](../index.md)

This isn't a real record type as found in the heap dump. It's an alternative to
[PrimitiveArrayDumpRecord](../-primitive-array-dump-record/index.md) for when you don't need the array content.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `PrimitiveArraySkipContentRecord(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, size: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, type: `[`PrimitiveType`](../../../../-primitive-type/index.md)`)`<br>This isn't a real record type as found in the heap dump. It's an alternative to [PrimitiveArrayDumpRecord](../-primitive-array-dump-record/index.md) for when you don't need the array content. |

### Properties

| Name | Summary |
|---|---|
| [id](id.md) | `val id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [size](size.md) | `val size: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [stackTraceSerialNumber](stack-trace-serial-number.md) | `val stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [type](type.md) | `val type: `[`PrimitiveType`](../../../../-primitive-type/index.md) |
