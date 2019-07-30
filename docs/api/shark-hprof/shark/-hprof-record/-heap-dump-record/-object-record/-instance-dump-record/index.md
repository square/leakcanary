[shark-hprof](../../../../../index.md) / [shark](../../../../index.md) / [HprofRecord](../../../index.md) / [HeapDumpRecord](../../index.md) / [ObjectRecord](../index.md) / [InstanceDumpRecord](./index.md)

# InstanceDumpRecord

`class InstanceDumpRecord : `[`HprofRecord.HeapDumpRecord.ObjectRecord`](../index.md)

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `InstanceDumpRecord(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, classId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, fieldValues: `[`ByteArray`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte-array/index.html)`)` |

### Properties

| Name | Summary |
|---|---|
| [classId](class-id.md) | `val classId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [fieldValues](field-values.md) | `val fieldValues: `[`ByteArray`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte-array/index.html)<br>Instance field values (this class, followed by super class, etc) |
| [id](id.md) | `val id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [stackTraceSerialNumber](stack-trace-serial-number.md) | `val stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
