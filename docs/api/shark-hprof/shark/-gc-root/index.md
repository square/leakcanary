//[shark-hprof](../../../index.md)/[shark](../index.md)/[GcRoot](index.md)

# GcRoot

[jvm]\
sealed class [GcRoot](index.md)

A GcRoot as identified by [HprofRecord.HeapDumpRecord.GcRootRecord](../-hprof-record/-heap-dump-record/-gc-root-record/index.md) in the heap dump.

## Types

| Name | Summary |
|---|---|
| [Debugger](-debugger/index.md) | [jvm]<br>class [Debugger](-debugger/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [GcRoot](index.md)<br>An object held by a connected debugger |
| [Finalizing](-finalizing/index.md) | [jvm]<br>class [Finalizing](-finalizing/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [GcRoot](index.md)<br>An object that is in a queue, waiting for a finalizer to run. |
| [InternedString](-interned-string/index.md) | [jvm]<br>class [InternedString](-interned-string/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [GcRoot](index.md)<br>An interned string, see [java.lang.String.intern](https://docs.oracle.com/javase/8/docs/api/java/lang/String.html#intern--). |
| [JavaFrame](-java-frame/index.md) | [jvm]<br>class [JavaFrame](-java-frame/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), threadSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), frameNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) : [GcRoot](index.md)<br>A java local variable |
| [JniGlobal](-jni-global/index.md) | [jvm]<br>class [JniGlobal](-jni-global/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), jniGlobalRefId: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [GcRoot](index.md)<br>A global variable in native code. |
| [JniLocal](-jni-local/index.md) | [jvm]<br>class [JniLocal](-jni-local/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), threadSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), frameNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) : [GcRoot](index.md)<br>A local variable in native code. |
| [JniMonitor](-jni-monitor/index.md) | [jvm]<br>class [JniMonitor](-jni-monitor/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), stackDepth: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) : [GcRoot](index.md)<br>It's unclear what this is, documentation welcome. |
| [MonitorUsed](-monitor-used/index.md) | [jvm]<br>class [MonitorUsed](-monitor-used/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [GcRoot](index.md)<br>Everything that called the wait() or notify() methods, or that is synchronized. |
| [NativeStack](-native-stack/index.md) | [jvm]<br>class [NativeStack](-native-stack/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), threadSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) : [GcRoot](index.md)<br>Input or output parameters in native code |
| [ReferenceCleanup](-reference-cleanup/index.md) | [jvm]<br>class [ReferenceCleanup](-reference-cleanup/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [GcRoot](index.md)<br>It's unclear what this is, documentation welcome. |
| [StickyClass](-sticky-class/index.md) | [jvm]<br>class [StickyClass](-sticky-class/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [GcRoot](index.md)<br>A system class |
| [ThreadBlock](-thread-block/index.md) | [jvm]<br>class [ThreadBlock](-thread-block/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), threadSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) : [GcRoot](index.md) |
| [ThreadObject](-thread-object/index.md) | [jvm]<br>class [ThreadObject](-thread-object/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), threadSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), stackTraceSerialNumber: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)) : [GcRoot](index.md)<br>A thread. |
| [Unknown](-unknown/index.md) | [jvm]<br>class [Unknown](-unknown/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [GcRoot](index.md)<br>An unknown gc root. |
| [Unreachable](-unreachable/index.md) | [jvm]<br>class [Unreachable](-unreachable/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [GcRoot](index.md)<br>An object that is unreachable from any other root, but not a root itself. |
| [VmInternal](-vm-internal/index.md) | [jvm]<br>class [VmInternal](-vm-internal/index.md)(id: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) : [GcRoot](index.md)<br>It's unclear what this is, documentation welcome. |

## Properties

| Name | Summary |
|---|---|
| [id](id.md) | [jvm]<br>abstract val [id](id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The object id of the object that this gc root references. |

## Inheritors

| Name |
|---|
| [Unknown](-unknown/index.md) |
| [JniGlobal](-jni-global/index.md) |
| [JniLocal](-jni-local/index.md) |
| [JavaFrame](-java-frame/index.md) |
| [NativeStack](-native-stack/index.md) |
| [StickyClass](-sticky-class/index.md) |
| [ThreadBlock](-thread-block/index.md) |
| [MonitorUsed](-monitor-used/index.md) |
| [ThreadObject](-thread-object/index.md) |
| [ReferenceCleanup](-reference-cleanup/index.md) |
| [VmInternal](-vm-internal/index.md) |
| [JniMonitor](-jni-monitor/index.md) |
| [InternedString](-interned-string/index.md) |
| [Finalizing](-finalizing/index.md) |
| [Debugger](-debugger/index.md) |
| [Unreachable](-unreachable/index.md) |
