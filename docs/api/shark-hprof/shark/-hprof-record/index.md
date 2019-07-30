[shark-hprof](../../index.md) / [shark](../index.md) / [HprofRecord](./index.md)

# HprofRecord

`sealed class HprofRecord`

A Hprof record. These data structure map 1:1 with how records are written in hprof files.

### Types

| Name | Summary |
|---|---|
| [HeapDumpEndRecord](-heap-dump-end-record.md) | `object HeapDumpEndRecord : `[`HprofRecord`](./index.md)<br>Terminates a series of heap dump segments. Concatenation of heap dump segments equals a heap dump. |
| [HeapDumpRecord](-heap-dump-record/index.md) | `sealed class HeapDumpRecord : `[`HprofRecord`](./index.md) |
| [LoadClassRecord](-load-class-record/index.md) | `class LoadClassRecord : `[`HprofRecord`](./index.md) |
| [StackFrameRecord](-stack-frame-record/index.md) | `class StackFrameRecord : `[`HprofRecord`](./index.md) |
| [StackTraceRecord](-stack-trace-record/index.md) | `class StackTraceRecord : `[`HprofRecord`](./index.md) |
| [StringRecord](-string-record/index.md) | `class StringRecord : `[`HprofRecord`](./index.md) |

### Inheritors

| Name | Summary |
|---|---|
| [HeapDumpEndRecord](-heap-dump-end-record.md) | `object HeapDumpEndRecord : `[`HprofRecord`](./index.md)<br>Terminates a series of heap dump segments. Concatenation of heap dump segments equals a heap dump. |
| [HeapDumpRecord](-heap-dump-record/index.md) | `sealed class HeapDumpRecord : `[`HprofRecord`](./index.md) |
| [LoadClassRecord](-load-class-record/index.md) | `class LoadClassRecord : `[`HprofRecord`](./index.md) |
| [StackFrameRecord](-stack-frame-record/index.md) | `class StackFrameRecord : `[`HprofRecord`](./index.md) |
| [StackTraceRecord](-stack-trace-record/index.md) | `class StackTraceRecord : `[`HprofRecord`](./index.md) |
| [StringRecord](-string-record/index.md) | `class StringRecord : `[`HprofRecord`](./index.md) |
