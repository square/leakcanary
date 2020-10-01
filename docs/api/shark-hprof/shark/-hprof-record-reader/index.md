[shark-hprof](../../index.md) / [shark](../index.md) / [HprofRecordReader](./index.md)

# HprofRecordReader

`class HprofRecordReader`

Reads hprof content from an Okio [BufferedSource](#).

Binary Dump Format reference: http://hg.openjdk.java.net/jdk6/jdk6/jdk/raw-file/tip/src/share
/demo/jvmti/hprof/manual.html#mozTocId848088

The Android Hprof format differs in some ways from that reference. This parser implementation
is largely adapted from https://android.googlesource.com/platform/tools/base/+/studio-master-dev
/perflib/src/main/java/com/android/tools/perflib

Not thread safe, should be used from a single thread.

### Properties

| Name | Summary |
|---|---|
| [bytesRead](bytes-read.md) | `var bytesRead: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>How many bytes this reader has read from [source](#). Can only increase. |

### Functions

| Name | Summary |
|---|---|
| [readBoolean](read-boolean.md) | `fun readBoolean(): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [readBooleanArray](read-boolean-array.md) | `fun readBooleanArray(arrayLength: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`BooleanArray`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean-array/index.html) |
| [readByte](read-byte.md) | `fun readByte(): `[`Byte`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte/index.html) |
| [readByteArray](read-byte-array.md) | `fun readByteArray(byteCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`ByteArray`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte-array/index.html) |
| [readChar](read-char.md) | `fun readChar(): `[`Char`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-char/index.html) |
| [readCharArray](read-char-array.md) | `fun readCharArray(arrayLength: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`CharArray`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-char-array/index.html) |
| [readClassDumpRecord](read-class-dump-record.md) | `fun readClassDumpRecord(): `[`HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord`](../-hprof-record/-heap-dump-record/-object-record/-class-dump-record/index.md)<br>Reads a full class record after a class dump tag. |
| [readDebuggerGcRootRecord](read-debugger-gc-root-record.md) | `fun readDebuggerGcRootRecord(): `[`GcRoot.Debugger`](../-gc-root/-debugger/index.md) |
| [readDouble](read-double.md) | `fun readDouble(): `[`Double`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double/index.html) |
| [readDoubleArray](read-double-array.md) | `fun readDoubleArray(arrayLength: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`DoubleArray`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double-array/index.html) |
| [readFinalizingGcRootRecord](read-finalizing-gc-root-record.md) | `fun readFinalizingGcRootRecord(): `[`GcRoot.Finalizing`](../-gc-root/-finalizing/index.md) |
| [readFloat](read-float.md) | `fun readFloat(): `[`Float`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-float/index.html) |
| [readFloatArray](read-float-array.md) | `fun readFloatArray(arrayLength: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`FloatArray`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-float-array/index.html) |
| [readHeapDumpInfoRecord](read-heap-dump-info-record.md) | `fun readHeapDumpInfoRecord(): `[`HprofRecord.HeapDumpRecord.HeapDumpInfoRecord`](../-hprof-record/-heap-dump-record/-heap-dump-info-record/index.md) |
| [readId](read-id.md) | `fun readId(): `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [readIdArray](read-id-array.md) | `fun readIdArray(arrayLength: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`LongArray`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long-array/index.html) |
| [readInstanceDumpRecord](read-instance-dump-record.md) | `fun readInstanceDumpRecord(): `[`HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord`](../-hprof-record/-heap-dump-record/-object-record/-instance-dump-record/index.md)<br>Reads a full instance record after a instance dump tag. |
| [readInt](read-int.md) | `fun readInt(): `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [readIntArray](read-int-array.md) | `fun readIntArray(arrayLength: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`IntArray`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int-array/index.html) |
| [readInternedStringGcRootRecord](read-interned-string-gc-root-record.md) | `fun readInternedStringGcRootRecord(): `[`GcRoot.InternedString`](../-gc-root/-interned-string/index.md) |
| [readJavaFrameGcRootRecord](read-java-frame-gc-root-record.md) | `fun readJavaFrameGcRootRecord(): `[`GcRoot.JavaFrame`](../-gc-root/-java-frame/index.md) |
| [readJniGlobalGcRootRecord](read-jni-global-gc-root-record.md) | `fun readJniGlobalGcRootRecord(): `[`GcRoot.JniGlobal`](../-gc-root/-jni-global/index.md) |
| [readJniLocalGcRootRecord](read-jni-local-gc-root-record.md) | `fun readJniLocalGcRootRecord(): `[`GcRoot.JniLocal`](../-gc-root/-jni-local/index.md) |
| [readJniMonitorGcRootRecord](read-jni-monitor-gc-root-record.md) | `fun readJniMonitorGcRootRecord(): `[`GcRoot.JniMonitor`](../-gc-root/-jni-monitor/index.md) |
| [readLoadClassRecord](read-load-class-record.md) | `fun readLoadClassRecord(): `[`HprofRecord.LoadClassRecord`](../-hprof-record/-load-class-record/index.md) |
| [readLong](read-long.md) | `fun readLong(): `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [readLongArray](read-long-array.md) | `fun readLongArray(arrayLength: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`LongArray`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long-array/index.html) |
| [readMonitorUsedGcRootRecord](read-monitor-used-gc-root-record.md) | `fun readMonitorUsedGcRootRecord(): `[`GcRoot.MonitorUsed`](../-gc-root/-monitor-used/index.md) |
| [readNativeStackGcRootRecord](read-native-stack-gc-root-record.md) | `fun readNativeStackGcRootRecord(): `[`GcRoot.NativeStack`](../-gc-root/-native-stack/index.md) |
| [readObjectArrayDumpRecord](read-object-array-dump-record.md) | `fun readObjectArrayDumpRecord(): `[`HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord`](../-hprof-record/-heap-dump-record/-object-record/-object-array-dump-record/index.md)<br>Reads a full object array record after a object array dump tag. |
| [readPrimitiveArrayDumpRecord](read-primitive-array-dump-record.md) | `fun readPrimitiveArrayDumpRecord(): `[`HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord`](../-hprof-record/-heap-dump-record/-object-record/-primitive-array-dump-record/index.md)<br>Reads a full primitive array record after a primitive array dump tag. |
| [readReferenceCleanupGcRootRecord](read-reference-cleanup-gc-root-record.md) | `fun readReferenceCleanupGcRootRecord(): `[`GcRoot.ReferenceCleanup`](../-gc-root/-reference-cleanup/index.md) |
| [readShort](read-short.md) | `fun readShort(): `[`Short`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-short/index.html) |
| [readShortArray](read-short-array.md) | `fun readShortArray(arrayLength: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`ShortArray`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-short-array/index.html) |
| [readStackFrameRecord](read-stack-frame-record.md) | `fun readStackFrameRecord(): `[`HprofRecord.StackFrameRecord`](../-hprof-record/-stack-frame-record/index.md) |
| [readStackTraceRecord](read-stack-trace-record.md) | `fun readStackTraceRecord(): `[`HprofRecord.StackTraceRecord`](../-hprof-record/-stack-trace-record/index.md) |
| [readStickyClassGcRootRecord](read-sticky-class-gc-root-record.md) | `fun readStickyClassGcRootRecord(): `[`GcRoot.StickyClass`](../-gc-root/-sticky-class/index.md) |
| [readString](read-string.md) | `fun readString(byteCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`, charset: `[`Charset`](https://docs.oracle.com/javase/6/docs/api/java/nio/charset/Charset.html)`): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [readStringRecord](read-string-record.md) | `fun readStringRecord(length: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`HprofRecord.StringRecord`](../-hprof-record/-string-record/index.md) |
| [readThreadBlockGcRootRecord](read-thread-block-gc-root-record.md) | `fun readThreadBlockGcRootRecord(): `[`GcRoot.ThreadBlock`](../-gc-root/-thread-block/index.md) |
| [readThreadObjectGcRootRecord](read-thread-object-gc-root-record.md) | `fun readThreadObjectGcRootRecord(): `[`GcRoot.ThreadObject`](../-gc-root/-thread-object/index.md) |
| [readUnknownGcRootRecord](read-unknown-gc-root-record.md) | `fun readUnknownGcRootRecord(): `[`GcRoot.Unknown`](../-gc-root/-unknown/index.md) |
| [readUnreachableGcRootRecord](read-unreachable-gc-root-record.md) | `fun readUnreachableGcRootRecord(): `[`GcRoot.Unreachable`](../-gc-root/-unreachable/index.md) |
| [readUnsignedByte](read-unsigned-byte.md) | `fun readUnsignedByte(): `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [readUnsignedInt](read-unsigned-int.md) | `fun readUnsignedInt(): `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [readUnsignedShort](read-unsigned-short.md) | `fun readUnsignedShort(): `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [readUtf8](read-utf8.md) | `fun readUtf8(byteCount: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [readValue](read-value.md) | `fun readValue(type: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`ValueHolder`](../-value-holder/index.md)<br>Reads a value in the heap dump, which can be a reference or a primitive type. |
| [readVmInternalGcRootRecord](read-vm-internal-gc-root-record.md) | `fun readVmInternalGcRootRecord(): `[`GcRoot.VmInternal`](../-gc-root/-vm-internal/index.md) |
| [sizeOf](size-of.md) | `fun sizeOf(type: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [skip](skip.md) | `fun skip(byteCount: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>`fun skip(byteCount: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [skipClassDumpConstantPool](skip-class-dump-constant-pool.md) | `fun skipClassDumpConstantPool(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [skipClassDumpFields](skip-class-dump-fields.md) | `fun skipClassDumpFields(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [skipClassDumpHeader](skip-class-dump-header.md) | `fun skipClassDumpHeader(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [skipClassDumpRecord](skip-class-dump-record.md) | `fun skipClassDumpRecord(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [skipClassDumpStaticFields](skip-class-dump-static-fields.md) | `fun skipClassDumpStaticFields(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [skipHeapDumpInfoRecord](skip-heap-dump-info-record.md) | `fun skipHeapDumpInfoRecord(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [skipInstanceDumpRecord](skip-instance-dump-record.md) | `fun skipInstanceDumpRecord(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [skipObjectArrayDumpRecord](skip-object-array-dump-record.md) | `fun skipObjectArrayDumpRecord(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [skipPrimitiveArrayDumpRecord](skip-primitive-array-dump-record.md) | `fun skipPrimitiveArrayDumpRecord(): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
