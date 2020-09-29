package shark

import okio.Source
import shark.HprofRecord.HeapDumpEndRecord
import shark.HprofRecord.HeapDumpRecord
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.HeapDumpInfoRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StackFrameRecord
import shark.HprofRecord.StackTraceRecord
import shark.HprofRecord.StringRecord
import shark.HprofRecordTag.CLASS_DUMP
import shark.HprofRecordTag.HEAP_DUMP
import shark.HprofRecordTag.HEAP_DUMP_END
import shark.HprofRecordTag.HEAP_DUMP_INFO
import shark.HprofRecordTag.HEAP_DUMP_SEGMENT
import shark.HprofRecordTag.INSTANCE_DUMP
import shark.HprofRecordTag.LOAD_CLASS
import shark.HprofRecordTag.OBJECT_ARRAY_DUMP
import shark.HprofRecordTag.PRIMITIVE_ARRAY_DUMP
import shark.HprofRecordTag.PRIMITIVE_ARRAY_NODATA
import shark.HprofRecordTag.ROOT_DEBUGGER
import shark.HprofRecordTag.ROOT_FINALIZING
import shark.HprofRecordTag.ROOT_INTERNED_STRING
import shark.HprofRecordTag.ROOT_JAVA_FRAME
import shark.HprofRecordTag.ROOT_JNI_GLOBAL
import shark.HprofRecordTag.ROOT_JNI_LOCAL
import shark.HprofRecordTag.ROOT_JNI_MONITOR
import shark.HprofRecordTag.ROOT_MONITOR_USED
import shark.HprofRecordTag.ROOT_NATIVE_STACK
import shark.HprofRecordTag.ROOT_REFERENCE_CLEANUP
import shark.HprofRecordTag.ROOT_STICKY_CLASS
import shark.HprofRecordTag.ROOT_THREAD_BLOCK
import shark.HprofRecordTag.ROOT_THREAD_OBJECT
import shark.HprofRecordTag.ROOT_UNKNOWN
import shark.HprofRecordTag.ROOT_UNREACHABLE
import shark.HprofRecordTag.ROOT_VM_INTERNAL
import shark.HprofRecordTag.STACK_FRAME
import shark.HprofRecordTag.STACK_TRACE
import shark.HprofRecordTag.STRING_IN_UTF8
import shark.PrimitiveType.Companion.REFERENCE_HPROF_TYPE
import shark.PrimitiveType.INT
import java.io.File
import java.util.EnumSet
import kotlin.reflect.KClass

/**
 * Reads the entire content of a Hprof source in one fell swoop.
 * Call [readerFor] to obtain a new instance.
 */
class StreamingHprofReader private constructor(
  private val sourceProvider: StreamingSourceProvider,
  private val header: HprofHeader
) {

  /**
   * Obtains a new source to read all hprof records from and calls [listener] back for each record
   * that matches one of the provided [recordTags].
   *
   * @return the number of bytes read from the source
   */
  @Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
  fun readRecords(
    recordTags: Set<HprofRecordTag>,
    listener: OnHprofRecordTagListener
  ): Long {
    return sourceProvider.openStreamingSource().use { source ->
      val reader = HprofRecordReader(header, source)
      reader.skip(header.recordsPosition)

      // Local ref optimizations
      val intByteSize = INT.byteSize
      val identifierByteSize = reader.sizeOf(REFERENCE_HPROF_TYPE)

      while (!source.exhausted()) {
        // type of the record
        val tag = reader.readUnsignedByte()

        // number of microseconds since the time stamp in the header
        reader.skip(intByteSize)

        // number of bytes that follow and belong to this record
        val length = reader.readUnsignedInt()

        when (tag) {
          STRING_IN_UTF8.tag -> {
            if (STRING_IN_UTF8 in recordTags) {
              listener.onHprofRecord(STRING_IN_UTF8, length, reader)
            } else {
              reader.skip(length)
            }
          }
          LOAD_CLASS.tag -> {
            if (LOAD_CLASS in recordTags) {
              listener.onHprofRecord(LOAD_CLASS, length, reader)
            } else {
              reader.skip(length)
            }
          }
          STACK_FRAME.tag -> {
            if (STACK_FRAME in recordTags) {
              listener.onHprofRecord(STACK_FRAME, length, reader)
            } else {
              reader.skip(length)
            }
          }
          STACK_TRACE.tag -> {
            if (STACK_TRACE in recordTags) {
              listener.onHprofRecord(STACK_TRACE, length, reader)
            } else {
              reader.skip(length)
            }
          }
          HEAP_DUMP.tag, HEAP_DUMP_SEGMENT.tag -> {
            val heapDumpStart = reader.bytesRead
            var previousTag = 0
            var previousTagPosition = 0L
            while (reader.bytesRead - heapDumpStart < length) {
              val heapDumpTagPosition = reader.bytesRead
              val heapDumpTag = reader.readUnsignedByte()
              when (heapDumpTag) {
                ROOT_UNKNOWN.tag -> {
                  if (ROOT_UNKNOWN in recordTags) {
                    listener.onHprofRecord(ROOT_UNKNOWN, -1, reader)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }
                ROOT_JNI_GLOBAL.tag -> {
                  if (ROOT_JNI_GLOBAL in recordTags) {
                    listener.onHprofRecord(ROOT_JNI_GLOBAL, -1, reader)
                  } else {
                    reader.skip(identifierByteSize + identifierByteSize)
                  }
                }
                ROOT_JNI_LOCAL.tag -> {
                  if (ROOT_JNI_LOCAL in recordTags) {
                    listener.onHprofRecord(ROOT_JNI_LOCAL, -1, reader)
                  } else {
                    reader.skip(identifierByteSize + intByteSize + intByteSize)
                  }
                }

                ROOT_JAVA_FRAME.tag -> {
                  if (ROOT_JAVA_FRAME in recordTags) {
                    listener.onHprofRecord(ROOT_JAVA_FRAME, -1, reader)
                  } else {
                    reader.skip(identifierByteSize + intByteSize + intByteSize)
                  }
                }

                ROOT_NATIVE_STACK.tag -> {
                  if (ROOT_NATIVE_STACK in recordTags) {
                    listener.onHprofRecord(ROOT_NATIVE_STACK, -1, reader)
                  } else {
                    reader.skip(identifierByteSize + intByteSize)
                  }
                }

                ROOT_STICKY_CLASS.tag -> {
                  if (ROOT_STICKY_CLASS in recordTags) {
                    listener.onHprofRecord(ROOT_STICKY_CLASS, -1, reader)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }
                ROOT_THREAD_BLOCK.tag -> {
                  if (ROOT_THREAD_BLOCK in recordTags) {
                    listener.onHprofRecord(ROOT_THREAD_BLOCK, -1, reader)
                  } else {
                    reader.skip(identifierByteSize + intByteSize)
                  }
                }

                ROOT_MONITOR_USED.tag -> {
                  if (ROOT_MONITOR_USED in recordTags) {
                    listener.onHprofRecord(ROOT_MONITOR_USED, -1, reader)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                ROOT_THREAD_OBJECT.tag -> {
                  if (ROOT_THREAD_OBJECT in recordTags) {
                    listener.onHprofRecord(ROOT_THREAD_OBJECT, -1, reader)
                  } else {
                    reader.skip(identifierByteSize + intByteSize + intByteSize)
                  }
                }

                ROOT_INTERNED_STRING.tag -> {
                  if (ROOT_INTERNED_STRING in recordTags) {
                    listener.onHprofRecord(ROOT_INTERNED_STRING, -1, reader)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                ROOT_FINALIZING.tag -> {
                  if (ROOT_FINALIZING in recordTags) {
                    listener.onHprofRecord(ROOT_FINALIZING, -1, reader)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                ROOT_DEBUGGER.tag -> {
                  if (ROOT_DEBUGGER in recordTags) {
                    listener.onHprofRecord(ROOT_DEBUGGER, -1, reader)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                ROOT_REFERENCE_CLEANUP.tag -> {
                  if (ROOT_REFERENCE_CLEANUP in recordTags) {
                    listener.onHprofRecord(ROOT_REFERENCE_CLEANUP, -1, reader)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                ROOT_VM_INTERNAL.tag -> {
                  if (ROOT_VM_INTERNAL in recordTags) {
                    listener.onHprofRecord(ROOT_VM_INTERNAL, -1, reader)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }

                ROOT_JNI_MONITOR.tag -> {
                  if (ROOT_JNI_MONITOR in recordTags) {
                    listener.onHprofRecord(ROOT_JNI_MONITOR, -1, reader)
                  } else {
                    reader.skip(identifierByteSize + intByteSize + intByteSize)
                  }
                }

                ROOT_UNREACHABLE.tag -> {
                  if (ROOT_UNREACHABLE in recordTags) {
                    listener.onHprofRecord(ROOT_UNREACHABLE, -1, reader)
                  } else {
                    reader.skip(identifierByteSize)
                  }
                }
                CLASS_DUMP.tag -> {
                  if (CLASS_DUMP in recordTags) {
                    listener.onHprofRecord(CLASS_DUMP, -1, reader)
                  } else {
                    reader.skipClassDumpRecord()
                  }
                }
                INSTANCE_DUMP.tag -> {
                  if (INSTANCE_DUMP in recordTags) {
                    listener.onHprofRecord(INSTANCE_DUMP, -1, reader)
                  } else {
                    reader.skipInstanceDumpRecord()
                  }
                }

                OBJECT_ARRAY_DUMP.tag -> {
                  if (OBJECT_ARRAY_DUMP in recordTags) {
                    listener.onHprofRecord(OBJECT_ARRAY_DUMP, -1, reader)
                  } else {
                    reader.skipObjectArrayDumpRecord()
                  }
                }

                PRIMITIVE_ARRAY_DUMP.tag -> {
                  if (PRIMITIVE_ARRAY_DUMP in recordTags) {
                    listener.onHprofRecord(PRIMITIVE_ARRAY_DUMP, -1, reader)
                  } else {
                    reader.skipPrimitiveArrayDumpRecord()
                  }
                }

                PRIMITIVE_ARRAY_NODATA.tag -> {
                  throw UnsupportedOperationException("$PRIMITIVE_ARRAY_NODATA cannot be parsed")
                }

                HEAP_DUMP_INFO.tag -> {
                  if (HEAP_DUMP_INFO in recordTags) {
                    listener.onHprofRecord(HEAP_DUMP_INFO, -1, reader)
                  } else {
                    reader.skipHeapDumpInfoRecord()
                  }
                }
                else -> throw IllegalStateException(
                    "Unknown tag ${"0x%02x".format(
                        heapDumpTag
                    )} at $heapDumpTagPosition after ${"0x%02x".format(
                        previousTag
                    )} at $previousTagPosition"
                )
              }
              previousTag = heapDumpTag
              previousTagPosition = heapDumpTagPosition
            }
          }
          HEAP_DUMP_END.tag -> {
            if (HEAP_DUMP_END in recordTags) {
              listener.onHprofRecord(HEAP_DUMP_END, length, reader)
            }
          }
          else -> {
            reader.skip(length)
          }
        }
      }
      reader.bytesRead
    }
  }



  companion object {

    /**
     * Creates a [StreamingHprofReader] for the provided [hprofFile]. [hprofHeader] will be read from
     * [hprofFile] unless you provide it.
     */
    fun readerFor(
      hprofFile: File,
      hprofHeader: HprofHeader = HprofHeader.parseHeaderOf(hprofFile)
    ): StreamingHprofReader {
      val sourceProvider = FileSourceProvider(hprofFile)
      return readerFor(sourceProvider, hprofHeader)
    }

    /**
     * Creates a [StreamingHprofReader] that will call [StreamingSourceProvider.openStreamingSource]
     * on every [readRecords] to obtain a [Source] to read the hprof data from. Before reading the
     * hprof records, [StreamingHprofReader] will skip [HprofHeader.recordsPosition] bytes.
     */
    fun readerFor(
      hprofSourceProvider: StreamingSourceProvider,
      hprofHeader: HprofHeader = hprofSourceProvider.openStreamingSource()
          .use { HprofHeader.parseHeaderOf(it) }
    ): StreamingHprofReader {
      return StreamingHprofReader(hprofSourceProvider, hprofHeader)
    }
  }
}
