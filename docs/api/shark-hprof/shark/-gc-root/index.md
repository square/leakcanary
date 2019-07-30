[shark-hprof](../../index.md) / [shark](../index.md) / [GcRoot](./index.md)

# GcRoot

`sealed class GcRoot`

A GcRoot as identified by [HprofRecord.HeapDumpRecord.GcRootRecord](../-hprof-record/-heap-dump-record/-gc-root-record/index.md) in the heap dump.

### Types

| Name | Summary |
|---|---|
| [Debugger](-debugger/index.md) | `class Debugger : `[`GcRoot`](./index.md)<br>An object held by a connected debugger |
| [Finalizing](-finalizing/index.md) | `class Finalizing : `[`GcRoot`](./index.md)<br>An object that is in a queue, waiting for a finalizer to run. |
| [InternedString](-interned-string/index.md) | `class InternedString : `[`GcRoot`](./index.md)<br>An interned string, see [java.lang.String.intern](https://docs.oracle.com/javase/6/docs/api/java/lang/String.html#intern()). |
| [JavaFrame](-java-frame/index.md) | `class JavaFrame : `[`GcRoot`](./index.md)<br>A java local variable |
| [JniGlobal](-jni-global/index.md) | `class JniGlobal : `[`GcRoot`](./index.md)<br>A global variable in native code. |
| [JniLocal](-jni-local/index.md) | `class JniLocal : `[`GcRoot`](./index.md)<br>A local variable in native code. |
| [JniMonitor](-jni-monitor/index.md) | `class JniMonitor : `[`GcRoot`](./index.md)<br>It's unclear what this is, documentation welcome. |
| [MonitorUsed](-monitor-used/index.md) | `class MonitorUsed : `[`GcRoot`](./index.md)<br>Everything that called the wait() or notify() methods, or that is synchronized. |
| [NativeStack](-native-stack/index.md) | `class NativeStack : `[`GcRoot`](./index.md)<br>Input or output parameters in native code |
| [ReferenceCleanup](-reference-cleanup/index.md) | `class ReferenceCleanup : `[`GcRoot`](./index.md)<br>It's unclear what this is, documentation welcome. |
| [StickyClass](-sticky-class/index.md) | `class StickyClass : `[`GcRoot`](./index.md)<br>A system class |
| [ThreadBlock](-thread-block/index.md) | `class ThreadBlock : `[`GcRoot`](./index.md) |
| [ThreadObject](-thread-object/index.md) | `class ThreadObject : `[`GcRoot`](./index.md)<br>A thread. |
| [Unknown](-unknown/index.md) | `class Unknown : `[`GcRoot`](./index.md)<br>An unknown gc root. |
| [Unreachable](-unreachable/index.md) | `class Unreachable : `[`GcRoot`](./index.md)<br>An object that is unreachable from any other root, but not a root itself. |
| [VmInternal](-vm-internal/index.md) | `class VmInternal : `[`GcRoot`](./index.md)<br>It's unclear what this is, documentation welcome. |

### Properties

| Name | Summary |
|---|---|
| [id](id.md) | `abstract val id: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |

### Inheritors

| Name | Summary |
|---|---|
| [Debugger](-debugger/index.md) | `class Debugger : `[`GcRoot`](./index.md)<br>An object held by a connected debugger |
| [Finalizing](-finalizing/index.md) | `class Finalizing : `[`GcRoot`](./index.md)<br>An object that is in a queue, waiting for a finalizer to run. |
| [InternedString](-interned-string/index.md) | `class InternedString : `[`GcRoot`](./index.md)<br>An interned string, see [java.lang.String.intern](https://docs.oracle.com/javase/6/docs/api/java/lang/String.html#intern()). |
| [JavaFrame](-java-frame/index.md) | `class JavaFrame : `[`GcRoot`](./index.md)<br>A java local variable |
| [JniGlobal](-jni-global/index.md) | `class JniGlobal : `[`GcRoot`](./index.md)<br>A global variable in native code. |
| [JniLocal](-jni-local/index.md) | `class JniLocal : `[`GcRoot`](./index.md)<br>A local variable in native code. |
| [JniMonitor](-jni-monitor/index.md) | `class JniMonitor : `[`GcRoot`](./index.md)<br>It's unclear what this is, documentation welcome. |
| [MonitorUsed](-monitor-used/index.md) | `class MonitorUsed : `[`GcRoot`](./index.md)<br>Everything that called the wait() or notify() methods, or that is synchronized. |
| [NativeStack](-native-stack/index.md) | `class NativeStack : `[`GcRoot`](./index.md)<br>Input or output parameters in native code |
| [ReferenceCleanup](-reference-cleanup/index.md) | `class ReferenceCleanup : `[`GcRoot`](./index.md)<br>It's unclear what this is, documentation welcome. |
| [StickyClass](-sticky-class/index.md) | `class StickyClass : `[`GcRoot`](./index.md)<br>A system class |
| [ThreadBlock](-thread-block/index.md) | `class ThreadBlock : `[`GcRoot`](./index.md) |
| [ThreadObject](-thread-object/index.md) | `class ThreadObject : `[`GcRoot`](./index.md)<br>A thread. |
| [Unknown](-unknown/index.md) | `class Unknown : `[`GcRoot`](./index.md)<br>An unknown gc root. |
| [Unreachable](-unreachable/index.md) | `class Unreachable : `[`GcRoot`](./index.md)<br>An object that is unreachable from any other root, but not a root itself. |
| [VmInternal](-vm-internal/index.md) | `class VmInternal : `[`GcRoot`](./index.md)<br>It's unclear what this is, documentation welcome. |
