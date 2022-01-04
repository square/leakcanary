//[shark-hprof](../../../../../../index.md)/[shark](../../../../index.md)/[HprofRecord](../../../index.md)/[HeapDumpRecord](../../index.md)/[ObjectRecord](../index.md)/[PrimitiveArrayDumpRecord](index.md)

# PrimitiveArrayDumpRecord

[jvm]\
sealed class [PrimitiveArrayDumpRecord](index.md) : [HprofRecord.HeapDumpRecord.ObjectRecord](../index.md)

## Types

| Name | Summary |
|---|---|
| [BooleanArrayDump](-boolean-array-dump/index.md) | [jvm]<br>class [BooleanArrayDump](-boolean-array-dump/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), array: [BooleanArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean-array/index.html)) : [HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord](index.md) |
| [ByteArrayDump](-byte-array-dump/index.md) | [jvm]<br>class [ByteArrayDump](-byte-array-dump/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), array: [ByteArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte-array/index.html)) : [HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord](index.md) |
| [CharArrayDump](-char-array-dump/index.md) | [jvm]<br>class [CharArrayDump](-char-array-dump/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), array: [CharArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-char-array/index.html)) : [HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord](index.md) |
| [DoubleArrayDump](-double-array-dump/index.md) | [jvm]<br>class [DoubleArrayDump](-double-array-dump/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), array: [DoubleArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double-array/index.html)) : [HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord](index.md) |
| [FloatArrayDump](-float-array-dump/index.md) | [jvm]<br>class [FloatArrayDump](-float-array-dump/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), array: [FloatArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-float-array/index.html)) : [HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord](index.md) |
| [IntArrayDump](-int-array-dump/index.md) | [jvm]<br>class [IntArrayDump](-int-array-dump/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), array: [IntArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int-array/index.html)) : [HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord](index.md) |
| [LongArrayDump](-long-array-dump/index.md) | [jvm]<br>class [LongArrayDump](-long-array-dump/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), array: [LongArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long-array/index.html)) : [HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord](index.md) |
| [ShortArrayDump](-short-array-dump/index.md) | [jvm]<br>class [ShortArrayDump](-short-array-dump/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), array: [ShortArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-short-array/index.html)) : [HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord](index.md) |

## Properties

| Name | Summary |
|---|---|
| [id](id.md) | [jvm]<br>abstract val [id](id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [size](size.md) | [jvm]<br>abstract val [size](size.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [stackTraceSerialNumber](stack-trace-serial-number.md) | [jvm]<br>abstract val [stackTraceSerialNumber](stack-trace-serial-number.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |

## Inheritors

| Name |
|---|
| [BooleanArrayDump](-boolean-array-dump/index.md) |
| [CharArrayDump](-char-array-dump/index.md) |
| [FloatArrayDump](-float-array-dump/index.md) |
| [DoubleArrayDump](-double-array-dump/index.md) |
| [ByteArrayDump](-byte-array-dump/index.md) |
| [ShortArrayDump](-short-array-dump/index.md) |
| [IntArrayDump](-int-array-dump/index.md) |
| [LongArrayDump](-long-array-dump/index.md) |
