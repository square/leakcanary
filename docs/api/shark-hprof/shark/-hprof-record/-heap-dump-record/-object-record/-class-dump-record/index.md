[shark-hprof](../../../../../index.md) / [shark](../../../../index.md) / [HprofRecord](../../../index.md) / [HeapDumpRecord](../../index.md) / [ObjectRecord](../index.md) / [ClassDumpRecord](./index.md)

# ClassDumpRecord

`class ClassDumpRecord : `[`HprofRecord.HeapDumpRecord.ObjectRecord`](../index.md)

### Types

| Name | Summary |
|---|---|
| [FieldRecord](-field-record/index.md) | `data class FieldRecord` |
| [StaticFieldRecord](-static-field-record/index.md) | `data class StaticFieldRecord` |

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `ClassDumpRecord(id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, superclassId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, classLoaderId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, signersId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, protectionDomainId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, instanceSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, staticFields: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord`](-static-field-record/index.md)`>, fields: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord`](-field-record/index.md)`>)` |

### Properties

| Name | Summary |
|---|---|
| [classLoaderId](class-loader-id.md) | `val classLoaderId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [fields](fields.md) | `val fields: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord`](-field-record/index.md)`>` |
| [id](id.md) | `val id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [instanceSize](instance-size.md) | `val instanceSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [protectionDomainId](protection-domain-id.md) | `val protectionDomainId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [signersId](signers-id.md) | `val signersId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [stackTraceSerialNumber](stack-trace-serial-number.md) | `val stackTraceSerialNumber: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [staticFields](static-fields.md) | `val staticFields: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<`[`HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord`](-static-field-record/index.md)`>` |
| [superclassId](superclass-id.md) | `val superclassId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
