//[shark-hprof](../../../../index.md)/[shark](../../index.md)/[HprofRecord](../index.md)/[HeapDumpRecord](index.md)

# HeapDumpRecord

[jvm]\
sealed class [HeapDumpRecord](index.md) : [HprofRecord](../index.md)

## Types

| Name | Summary |
|---|---|
| [GcRootRecord](-gc-root-record/index.md) | [jvm]<br>class [GcRootRecord](-gc-root-record/index.md)(gcRoot: [GcRoot](../../-gc-root/index.md)) : [HprofRecord.HeapDumpRecord](index.md) |
| [HeapDumpInfoRecord](-heap-dump-info-record/index.md) | [jvm]<br>class [HeapDumpInfoRecord](-heap-dump-info-record/index.md)(heapId: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), heapNameStringId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [HprofRecord.HeapDumpRecord](index.md) |
| [ObjectRecord](-object-record/index.md) | [jvm]<br>sealed class [ObjectRecord](-object-record/index.md) : [HprofRecord.HeapDumpRecord](index.md) |

## Inheritors

| Name |
|---|
| [GcRootRecord](-gc-root-record/index.md) |
| [ObjectRecord](-object-record/index.md) |
| [HeapDumpInfoRecord](-heap-dump-info-record/index.md) |
