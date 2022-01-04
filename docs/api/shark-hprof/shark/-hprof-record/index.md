//[shark-hprof](../../../index.md)/[shark](../index.md)/[HprofRecord](index.md)

# HprofRecord

[jvm]\
sealed class [HprofRecord](index.md)

A Hprof record. These data structure map 1:1 with how records are written in hprof files.

## Types

| Name | Summary |
|---|---|
| [HeapDumpEndRecord](-heap-dump-end-record/index.md) | [jvm]<br>object [HeapDumpEndRecord](-heap-dump-end-record/index.md) : [HprofRecord](index.md)<br>Terminates a series of heap dump segments. Concatenation of heap dump segments equals a heap dump. |
| [HeapDumpRecord](-heap-dump-record/index.md) | [jvm]<br>sealed class [HeapDumpRecord](-heap-dump-record/index.md) : [HprofRecord](index.md) |
| [LoadClassRecord](-load-class-record/index.md) | [jvm]<br>class [LoadClassRecord](-load-class-record/index.md)(classSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), classNameStringId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [HprofRecord](index.md) |
| [StackFrameRecord](-stack-frame-record/index.md) | [jvm]<br>class [StackFrameRecord](-stack-frame-record/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), methodNameStringId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), methodSignatureStringId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), sourceFileNameStringId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), classSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), lineNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) : [HprofRecord](index.md) |
| [StackTraceRecord](-stack-trace-record/index.md) | [jvm]<br>class [StackTraceRecord](-stack-trace-record/index.md)(stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), threadSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), stackFrameIds: [LongArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long-array/index.html)) : [HprofRecord](index.md) |
| [StringRecord](-string-record/index.md) | [jvm]<br>class [StringRecord](-string-record/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), string: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) : [HprofRecord](index.md) |

## Inheritors

| Name |
|---|
| [StringRecord](-string-record/index.md) |
| [LoadClassRecord](-load-class-record/index.md) |
| [HeapDumpEndRecord](-heap-dump-end-record/index.md) |
| [StackFrameRecord](-stack-frame-record/index.md) |
| [StackTraceRecord](-stack-trace-record/index.md) |
| [HeapDumpRecord](-heap-dump-record/index.md) |
