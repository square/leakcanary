//[shark-hprof](../../../../../index.md)/[shark](../../../index.md)/[HprofRecord](../../index.md)/[HeapDumpRecord](../index.md)/[ObjectRecord](index.md)

# ObjectRecord

[jvm]\
sealed class [ObjectRecord](index.md) : [HprofRecord.HeapDumpRecord](../index.md)

## Types

| Name | Summary |
|---|---|
| [ClassDumpRecord](-class-dump-record/index.md) | [jvm]<br>class [ClassDumpRecord](-class-dump-record/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), superclassId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), classLoaderId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), signersId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), protectionDomainId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), instanceSize: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), staticFields: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord](-class-dump-record/-static-field-record/index.md)&gt;, fields: [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord](-class-dump-record/-field-record/index.md)&gt;) : [HprofRecord.HeapDumpRecord.ObjectRecord](index.md) |
| [InstanceDumpRecord](-instance-dump-record/index.md) | [jvm]<br>class [InstanceDumpRecord](-instance-dump-record/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), classId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), fieldValues: [ByteArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte-array/index.html)) : [HprofRecord.HeapDumpRecord.ObjectRecord](index.md) |
| [ObjectArrayDumpRecord](-object-array-dump-record/index.md) | [jvm]<br>class [ObjectArrayDumpRecord](-object-array-dump-record/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), arrayClassId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), elementIds: [LongArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long-array/index.html)) : [HprofRecord.HeapDumpRecord.ObjectRecord](index.md) |
| [PrimitiveArrayDumpRecord](-primitive-array-dump-record/index.md) | [jvm]<br>sealed class [PrimitiveArrayDumpRecord](-primitive-array-dump-record/index.md) : [HprofRecord.HeapDumpRecord.ObjectRecord](index.md) |

## Inheritors

| Name |
|---|
| [ClassDumpRecord](-class-dump-record/index.md) |
| [InstanceDumpRecord](-instance-dump-record/index.md) |
| [ObjectArrayDumpRecord](-object-array-dump-record/index.md) |
| [PrimitiveArrayDumpRecord](-primitive-array-dump-record/index.md) |
