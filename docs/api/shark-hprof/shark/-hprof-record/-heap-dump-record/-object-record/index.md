[shark-hprof](../../../../index.md) / [shark](../../../index.md) / [HprofRecord](../../index.md) / [HeapDumpRecord](../index.md) / [ObjectRecord](./index.md)

# ObjectRecord

`sealed class ObjectRecord : `[`HprofRecord.HeapDumpRecord`](../index.md)

### Types

| Name | Summary |
|---|---|
| [ClassDumpRecord](-class-dump-record/index.md) | `class ClassDumpRecord : `[`HprofRecord.HeapDumpRecord.ObjectRecord`](./index.md) |
| [InstanceDumpRecord](-instance-dump-record/index.md) | `class InstanceDumpRecord : `[`HprofRecord.HeapDumpRecord.ObjectRecord`](./index.md) |
| [ObjectArrayDumpRecord](-object-array-dump-record/index.md) | `class ObjectArrayDumpRecord : `[`HprofRecord.HeapDumpRecord.ObjectRecord`](./index.md) |
| [PrimitiveArrayDumpRecord](-primitive-array-dump-record/index.md) | `sealed class PrimitiveArrayDumpRecord : `[`HprofRecord.HeapDumpRecord.ObjectRecord`](./index.md)<br>Note: we could move the arrays to the parent class as a ByteString or ByteArray and then each subtype can create a new array of the right type if needed. However, experimenting with live parsing has shown that we never to read arrays except when we want to display leak trace information, in which case we do need the data. |

### Inheritors

| Name | Summary |
|---|---|
| [ClassDumpRecord](-class-dump-record/index.md) | `class ClassDumpRecord : `[`HprofRecord.HeapDumpRecord.ObjectRecord`](./index.md) |
| [InstanceDumpRecord](-instance-dump-record/index.md) | `class InstanceDumpRecord : `[`HprofRecord.HeapDumpRecord.ObjectRecord`](./index.md) |
| [ObjectArrayDumpRecord](-object-array-dump-record/index.md) | `class ObjectArrayDumpRecord : `[`HprofRecord.HeapDumpRecord.ObjectRecord`](./index.md) |
| [PrimitiveArrayDumpRecord](-primitive-array-dump-record/index.md) | `sealed class PrimitiveArrayDumpRecord : `[`HprofRecord.HeapDumpRecord.ObjectRecord`](./index.md)<br>Note: we could move the arrays to the parent class as a ByteString or ByteArray and then each subtype can create a new array of the right type if needed. However, experimenting with live parsing has shown that we never to read arrays except when we want to display leak trace information, in which case we do need the data. |
