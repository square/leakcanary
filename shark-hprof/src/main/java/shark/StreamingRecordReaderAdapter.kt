package shark

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
import shark.HprofRecordTag.HEAP_DUMP_END
import shark.HprofRecordTag.HEAP_DUMP_INFO
import shark.HprofRecordTag.INSTANCE_DUMP
import shark.HprofRecordTag.LOAD_CLASS
import shark.HprofRecordTag.OBJECT_ARRAY_DUMP
import shark.HprofRecordTag.PRIMITIVE_ARRAY_DUMP
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
import java.util.EnumSet
import kotlin.reflect.KClass

/**
 * Wraps a [StreamingHprofReader] to provide a higher level API that streams [HprofRecord]
 * instances.
 */
class StreamingRecordReaderAdapter(private val streamingHprofReader: StreamingHprofReader) {

  /**
   * Obtains a new source to read all hprof records from and calls [listener] back for each record
   * that matches one of the provided [recordTypes].
   *
   * @return the number of bytes read from the source
   */
  @Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
  fun readRecords(
    recordTypes: Set<KClass<out HprofRecord>>,
    listener: OnHprofRecordListener
  ): Long {
    val recordTags = recordTypes.asHprofTags()
    return streamingHprofReader.readRecords(
        recordTags, OnHprofRecordTagListener { tag, length, reader ->
      when (tag) {
        STRING_IN_UTF8 -> {
          val recordPosition = reader.bytesRead
          val record = reader.readStringRecord(length)
          listener.onHprofRecord(recordPosition, record)
        }
        LOAD_CLASS -> {
          val recordPosition = reader.bytesRead
          val record = reader.readLoadClassRecord()
          listener.onHprofRecord(recordPosition, record)
        }
        STACK_FRAME -> {
          val recordPosition = reader.bytesRead
          val record = reader.readStackFrameRecord()
          listener.onHprofRecord(recordPosition, record)
        }
        STACK_TRACE -> {
          val recordPosition = reader.bytesRead
          val record = reader.readStackTraceRecord()
          listener.onHprofRecord(recordPosition, record)
        }
        ROOT_UNKNOWN -> {
          val recordPosition = reader.bytesRead
          val record = reader.readUnknownGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(record))
        }
        ROOT_JNI_GLOBAL -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readJniGlobalGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }
        ROOT_JNI_LOCAL -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readJniLocalGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_JAVA_FRAME -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readJavaFrameGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_NATIVE_STACK -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readNativeStackGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_STICKY_CLASS -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readStickyClassGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_THREAD_BLOCK -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readThreadBlockGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_MONITOR_USED -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readMonitorUsedGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_THREAD_OBJECT -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readThreadObjectGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_INTERNED_STRING -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readInternedStringGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_FINALIZING -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readFinalizingGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_DEBUGGER -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readDebuggerGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_REFERENCE_CLEANUP -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readReferenceCleanupGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_VM_INTERNAL -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readVmInternalGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_JNI_MONITOR -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readJniMonitorGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }

        ROOT_UNREACHABLE -> {
          val recordPosition = reader.bytesRead
          val gcRootRecord = reader.readUnreachableGcRootRecord()
          listener.onHprofRecord(recordPosition, GcRootRecord(gcRootRecord))
        }
        CLASS_DUMP -> {
          val recordPosition = reader.bytesRead
          val record = reader.readClassDumpRecord()
          listener.onHprofRecord(recordPosition, record)
        }
        INSTANCE_DUMP -> {
          val recordPosition = reader.bytesRead
          val record = reader.readInstanceDumpRecord()
          listener.onHprofRecord(recordPosition, record)
        }

        OBJECT_ARRAY_DUMP -> {
          val recordPosition = reader.bytesRead
          val arrayRecord = reader.readObjectArrayDumpRecord()
          listener.onHprofRecord(recordPosition, arrayRecord)
        }

        PRIMITIVE_ARRAY_DUMP -> {
          val recordPosition = reader.bytesRead
          val record = reader.readPrimitiveArrayDumpRecord()
          listener.onHprofRecord(recordPosition, record)
        }

        HEAP_DUMP_INFO -> {
          val recordPosition = reader.bytesRead
          val record = reader.readHeapDumpInfoRecord()
          listener.onHprofRecord(recordPosition, record)
        }
        HEAP_DUMP_END -> {
          val recordPosition = reader.bytesRead
          val record = HeapDumpEndRecord
          listener.onHprofRecord(recordPosition, record)
        }
        else -> error("Unexpected heap dump tag $tag at position ${reader.bytesRead}")
      }
    })
  }

  companion object {
    fun StreamingHprofReader.asStreamingRecordReader() = StreamingRecordReaderAdapter(this)

    fun Set<KClass<out HprofRecord>>.asHprofTags(): EnumSet<HprofRecordTag> {
      val recordTypes = this
      return if (HprofRecord::class in recordTypes) {
        EnumSet.allOf(HprofRecordTag::class.java)
      } else {
        EnumSet.noneOf(HprofRecordTag::class.java).apply {
          if (StringRecord::class in recordTypes) {
            add(STRING_IN_UTF8)
          }
          if (LoadClassRecord::class in recordTypes) {
            add(LOAD_CLASS)
          }
          if (HeapDumpEndRecord::class in recordTypes) {
            add(HEAP_DUMP_END)
          }
          if (StackFrameRecord::class in recordTypes) {
            add(STACK_FRAME)
          }
          if (StackTraceRecord::class in recordTypes) {
            add(STACK_TRACE)
          }
          if (HeapDumpInfoRecord::class in recordTypes) {
            add(HEAP_DUMP_INFO)
          }
          val readAllHeapDumpRecords = HeapDumpRecord::class in recordTypes
          if (readAllHeapDumpRecords || GcRootRecord::class in recordTypes) {
            addAll(HprofRecordTag.rootTags)
          }
          val readAllObjectRecords = readAllHeapDumpRecords || ObjectRecord::class in recordTypes
          if (readAllObjectRecords || ClassDumpRecord::class in recordTypes) {
            add(CLASS_DUMP)
          }
          if (readAllObjectRecords || InstanceDumpRecord::class in recordTypes) {
            add(INSTANCE_DUMP)
          }
          if (readAllObjectRecords || ObjectArrayDumpRecord::class in recordTypes) {
            add(OBJECT_ARRAY_DUMP)
          }
          if (readAllObjectRecords || PrimitiveArrayDumpRecord::class in recordTypes) {
            add(PRIMITIVE_ARRAY_DUMP)
          }
        }
      }
    }
  }

}