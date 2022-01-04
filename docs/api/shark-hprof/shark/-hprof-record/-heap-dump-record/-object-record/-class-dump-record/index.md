//[shark-hprof](../../../../../../index.md)/[shark](../../../../index.md)/[HprofRecord](../../../index.md)/[HeapDumpRecord](../../index.md)/[ObjectRecord](../index.md)/[ClassDumpRecord](index.md)

# ClassDumpRecord

[jvm]\
class [ClassDumpRecord](index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), superclassId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), classLoaderId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), signersId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), protectionDomainId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), instanceSize: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), staticFields: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord](-static-field-record/index.md)&gt;, fields: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord](-field-record/index.md)&gt;) : [HprofRecord.HeapDumpRecord.ObjectRecord](../index.md)

## Types

| Name | Summary |
|---|---|
| [FieldRecord](-field-record/index.md) | [jvm]<br>data class [FieldRecord](-field-record/index.md)(nameStringId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), type: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) |
| [StaticFieldRecord](-static-field-record/index.md) | [jvm]<br>data class [StaticFieldRecord](-static-field-record/index.md)(nameStringId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), type: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), value: [ValueHolder](../../../../-value-holder/index.md)) |

## Properties

| Name | Summary |
|---|---|
| [classLoaderId](class-loader-id.md) | [jvm]<br>val [classLoaderId](class-loader-id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [fields](fields.md) | [jvm]<br>val [fields](fields.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord](-field-record/index.md)&gt; |
| [id](id.md) | [jvm]<br>val [id](id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [instanceSize](instance-size.md) | [jvm]<br>val [instanceSize](instance-size.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [protectionDomainId](protection-domain-id.md) | [jvm]<br>val [protectionDomainId](protection-domain-id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [signersId](signers-id.md) | [jvm]<br>val [signersId](signers-id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [stackTraceSerialNumber](stack-trace-serial-number.md) | [jvm]<br>val [stackTraceSerialNumber](stack-trace-serial-number.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [staticFields](static-fields.md) | [jvm]<br>val [staticFields](static-fields.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord](-static-field-record/index.md)&gt; |
| [superclassId](superclass-id.md) | [jvm]<br>val [superclassId](superclass-id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
