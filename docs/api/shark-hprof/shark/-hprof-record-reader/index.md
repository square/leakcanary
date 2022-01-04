//[shark-hprof](../../../index.md)/[shark](../index.md)/[HprofRecordReader](index.md)

# HprofRecordReader

[jvm]\
class [HprofRecordReader](index.md)

Reads hprof content from an Okio [BufferedSource](https://square.github.io/okio/2.x/okio/okio/-buffered-source/index.html).

Binary Dump Format reference: http://hg.openjdk.java.net/jdk6/jdk6/jdk/raw-file/tip/src/share /demo/jvmti/hprof/manual.html#mozTocId848088

The Android Hprof format differs in some ways from that reference. This parser implementation is largely adapted from https://android.googlesource.com/platform/tools/base/+/studio-master-dev /perflib/src/main/java/com/android/tools/perflib

Not thread safe, should be used from a single thread.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [readBoolean](read-boolean.md) | [jvm]<br>fun [readBoolean](read-boolean.md)(): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [readBooleanArray](read-boolean-array.md) | [jvm]<br>fun [readBooleanArray](read-boolean-array.md)(arrayLength: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [BooleanArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean-array/index.html) |
| [readByte](read-byte.md) | [jvm]<br>fun [readByte](read-byte.md)(): [Byte](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte/index.html) |
| [readByteArray](read-byte-array.md) | [jvm]<br>fun [readByteArray](read-byte-array.md)(byteCount: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [ByteArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte-array/index.html) |
| [readChar](read-char.md) | [jvm]<br>fun [readChar](read-char.md)(): [Char](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-char/index.html) |
| [readCharArray](read-char-array.md) | [jvm]<br>fun [readCharArray](read-char-array.md)(arrayLength: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [CharArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-char-array/index.html) |
| [readClassDumpRecord](read-class-dump-record.md) | [jvm]<br>fun [readClassDumpRecord](read-class-dump-record.md)(): [HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord](../-hprof-record/-heap-dump-record/-object-record/-class-dump-record/index.md)<br>Reads a full class record after a class dump tag. |
| [readDebuggerGcRootRecord](read-debugger-gc-root-record.md) | [jvm]<br>fun [readDebuggerGcRootRecord](read-debugger-gc-root-record.md)(): [GcRoot.Debugger](../-gc-root/-debugger/index.md) |
| [readDouble](read-double.md) | [jvm]<br>fun [readDouble](read-double.md)(): [Double](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double/index.html) |
| [readDoubleArray](read-double-array.md) | [jvm]<br>fun [readDoubleArray](read-double-array.md)(arrayLength: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [DoubleArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double-array/index.html) |
| [readFinalizingGcRootRecord](read-finalizing-gc-root-record.md) | [jvm]<br>fun [readFinalizingGcRootRecord](read-finalizing-gc-root-record.md)(): [GcRoot.Finalizing](../-gc-root/-finalizing/index.md) |
| [readFloat](read-float.md) | [jvm]<br>fun [readFloat](read-float.md)(): [Float](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-float/index.html) |
| [readFloatArray](read-float-array.md) | [jvm]<br>fun [readFloatArray](read-float-array.md)(arrayLength: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [FloatArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-float-array/index.html) |
| [readHeapDumpInfoRecord](read-heap-dump-info-record.md) | [jvm]<br>fun [readHeapDumpInfoRecord](read-heap-dump-info-record.md)(): [HprofRecord.HeapDumpRecord.HeapDumpInfoRecord](../-hprof-record/-heap-dump-record/-heap-dump-info-record/index.md) |
| [readId](read-id.md) | [jvm]<br>fun [readId](read-id.md)(): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [readIdArray](read-id-array.md) | [jvm]<br>fun [readIdArray](read-id-array.md)(arrayLength: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [LongArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long-array/index.html) |
| [readInstanceDumpRecord](read-instance-dump-record.md) | [jvm]<br>fun [readInstanceDumpRecord](read-instance-dump-record.md)(): [HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord](../-hprof-record/-heap-dump-record/-object-record/-instance-dump-record/index.md)<br>Reads a full instance record after a instance dump tag. |
| [readInt](read-int.md) | [jvm]<br>fun [readInt](read-int.md)(): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [readIntArray](read-int-array.md) | [jvm]<br>fun [readIntArray](read-int-array.md)(arrayLength: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [IntArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int-array/index.html) |
| [readInternedStringGcRootRecord](read-interned-string-gc-root-record.md) | [jvm]<br>fun [readInternedStringGcRootRecord](read-interned-string-gc-root-record.md)(): [GcRoot.InternedString](../-gc-root/-interned-string/index.md) |
| [readJavaFrameGcRootRecord](read-java-frame-gc-root-record.md) | [jvm]<br>fun [readJavaFrameGcRootRecord](read-java-frame-gc-root-record.md)(): [GcRoot.JavaFrame](../-gc-root/-java-frame/index.md) |
| [readJniGlobalGcRootRecord](read-jni-global-gc-root-record.md) | [jvm]<br>fun [readJniGlobalGcRootRecord](read-jni-global-gc-root-record.md)(): [GcRoot.JniGlobal](../-gc-root/-jni-global/index.md) |
| [readJniLocalGcRootRecord](read-jni-local-gc-root-record.md) | [jvm]<br>fun [readJniLocalGcRootRecord](read-jni-local-gc-root-record.md)(): [GcRoot.JniLocal](../-gc-root/-jni-local/index.md) |
| [readJniMonitorGcRootRecord](read-jni-monitor-gc-root-record.md) | [jvm]<br>fun [readJniMonitorGcRootRecord](read-jni-monitor-gc-root-record.md)(): [GcRoot.JniMonitor](../-gc-root/-jni-monitor/index.md) |
| [readLoadClassRecord](read-load-class-record.md) | [jvm]<br>fun [readLoadClassRecord](read-load-class-record.md)(): [HprofRecord.LoadClassRecord](../-hprof-record/-load-class-record/index.md) |
| [readLong](read-long.md) | [jvm]<br>fun [readLong](read-long.md)(): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [readLongArray](read-long-array.md) | [jvm]<br>fun [readLongArray](read-long-array.md)(arrayLength: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [LongArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long-array/index.html) |
| [readMonitorUsedGcRootRecord](read-monitor-used-gc-root-record.md) | [jvm]<br>fun [readMonitorUsedGcRootRecord](read-monitor-used-gc-root-record.md)(): [GcRoot.MonitorUsed](../-gc-root/-monitor-used/index.md) |
| [readNativeStackGcRootRecord](read-native-stack-gc-root-record.md) | [jvm]<br>fun [readNativeStackGcRootRecord](read-native-stack-gc-root-record.md)(): [GcRoot.NativeStack](../-gc-root/-native-stack/index.md) |
| [readObjectArrayDumpRecord](read-object-array-dump-record.md) | [jvm]<br>fun [readObjectArrayDumpRecord](read-object-array-dump-record.md)(): [HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord](../-hprof-record/-heap-dump-record/-object-record/-object-array-dump-record/index.md)<br>Reads a full object array record after a object array dump tag. |
| [readPrimitiveArrayDumpRecord](read-primitive-array-dump-record.md) | [jvm]<br>fun [readPrimitiveArrayDumpRecord](read-primitive-array-dump-record.md)(): [HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord](../-hprof-record/-heap-dump-record/-object-record/-primitive-array-dump-record/index.md)<br>Reads a full primitive array record after a primitive array dump tag. |
| [readReferenceCleanupGcRootRecord](read-reference-cleanup-gc-root-record.md) | [jvm]<br>fun [readReferenceCleanupGcRootRecord](read-reference-cleanup-gc-root-record.md)(): [GcRoot.ReferenceCleanup](../-gc-root/-reference-cleanup/index.md) |
| [readShort](read-short.md) | [jvm]<br>fun [readShort](read-short.md)(): [Short](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-short/index.html) |
| [readShortArray](read-short-array.md) | [jvm]<br>fun [readShortArray](read-short-array.md)(arrayLength: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [ShortArray](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-short-array/index.html) |
| [readStackFrameRecord](read-stack-frame-record.md) | [jvm]<br>fun [readStackFrameRecord](read-stack-frame-record.md)(): [HprofRecord.StackFrameRecord](../-hprof-record/-stack-frame-record/index.md) |
| [readStackTraceRecord](read-stack-trace-record.md) | [jvm]<br>fun [readStackTraceRecord](read-stack-trace-record.md)(): [HprofRecord.StackTraceRecord](../-hprof-record/-stack-trace-record/index.md) |
| [readStickyClassGcRootRecord](read-sticky-class-gc-root-record.md) | [jvm]<br>fun [readStickyClassGcRootRecord](read-sticky-class-gc-root-record.md)(): [GcRoot.StickyClass](../-gc-root/-sticky-class/index.md) |
| [readString](read-string.md) | [jvm]<br>fun [readString](read-string.md)(byteCount: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html), charset: [Charset](https://docs.oracle.com/javase/8/docs/api/java/nio/charset/Charset.html)): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [readStringRecord](read-string-record.md) | [jvm]<br>fun [readStringRecord](read-string-record.md)(length: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)): [HprofRecord.StringRecord](../-hprof-record/-string-record/index.md) |
| [readThreadBlockGcRootRecord](read-thread-block-gc-root-record.md) | [jvm]<br>fun [readThreadBlockGcRootRecord](read-thread-block-gc-root-record.md)(): [GcRoot.ThreadBlock](../-gc-root/-thread-block/index.md) |
| [readThreadObjectGcRootRecord](read-thread-object-gc-root-record.md) | [jvm]<br>fun [readThreadObjectGcRootRecord](read-thread-object-gc-root-record.md)(): [GcRoot.ThreadObject](../-gc-root/-thread-object/index.md) |
| [readUnknownGcRootRecord](read-unknown-gc-root-record.md) | [jvm]<br>fun [readUnknownGcRootRecord](read-unknown-gc-root-record.md)(): [GcRoot.Unknown](../-gc-root/-unknown/index.md) |
| [readUnreachableGcRootRecord](read-unreachable-gc-root-record.md) | [jvm]<br>fun [readUnreachableGcRootRecord](read-unreachable-gc-root-record.md)(): [GcRoot.Unreachable](../-gc-root/-unreachable/index.md) |
| [readUnsignedByte](read-unsigned-byte.md) | [jvm]<br>fun [readUnsignedByte](read-unsigned-byte.md)(): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [readUnsignedInt](read-unsigned-int.md) | [jvm]<br>fun [readUnsignedInt](read-unsigned-int.md)(): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [readUnsignedShort](read-unsigned-short.md) | [jvm]<br>fun [readUnsignedShort](read-unsigned-short.md)(): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [readUtf8](read-utf8.md) | [jvm]<br>fun [readUtf8](read-utf8.md)(byteCount: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [readValue](read-value.md) | [jvm]<br>fun [readValue](read-value.md)(type: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [ValueHolder](../-value-holder/index.md)<br>Reads a value in the heap dump, which can be a reference or a primitive type. |
| [readVmInternalGcRootRecord](read-vm-internal-gc-root-record.md) | [jvm]<br>fun [readVmInternalGcRootRecord](read-vm-internal-gc-root-record.md)(): [GcRoot.VmInternal](../-gc-root/-vm-internal/index.md) |
| [sizeOf](size-of.md) | [jvm]<br>fun [sizeOf](size-of.md)(type: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
| [skip](skip.md) | [jvm]<br>fun [skip](skip.md)(byteCount: [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html))<br>fun [skip](skip.md)(byteCount: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)) |
| [skipClassDumpConstantPool](skip-class-dump-constant-pool.md) | [jvm]<br>fun [skipClassDumpConstantPool](skip-class-dump-constant-pool.md)() |
| [skipClassDumpFields](skip-class-dump-fields.md) | [jvm]<br>fun [skipClassDumpFields](skip-class-dump-fields.md)() |
| [skipClassDumpHeader](skip-class-dump-header.md) | [jvm]<br>fun [skipClassDumpHeader](skip-class-dump-header.md)() |
| [skipClassDumpRecord](skip-class-dump-record.md) | [jvm]<br>fun [skipClassDumpRecord](skip-class-dump-record.md)() |
| [skipClassDumpStaticFields](skip-class-dump-static-fields.md) | [jvm]<br>fun [skipClassDumpStaticFields](skip-class-dump-static-fields.md)() |
| [skipHeapDumpInfoRecord](skip-heap-dump-info-record.md) | [jvm]<br>fun [skipHeapDumpInfoRecord](skip-heap-dump-info-record.md)() |
| [skipInstanceDumpRecord](skip-instance-dump-record.md) | [jvm]<br>fun [skipInstanceDumpRecord](skip-instance-dump-record.md)() |
| [skipObjectArrayDumpRecord](skip-object-array-dump-record.md) | [jvm]<br>fun [skipObjectArrayDumpRecord](skip-object-array-dump-record.md)() |
| [skipPrimitiveArrayDumpRecord](skip-primitive-array-dump-record.md) | [jvm]<br>fun [skipPrimitiveArrayDumpRecord](skip-primitive-array-dump-record.md)() |

## Properties

| Name | Summary |
|---|---|
| [bytesRead](bytes-read.md) | [jvm]<br>var [bytesRead](bytes-read.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) = 0<br>How many bytes this reader has read from source. Can only increase. |
