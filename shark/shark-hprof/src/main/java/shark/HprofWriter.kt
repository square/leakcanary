package shark

import java.io.Closeable
import java.io.File
import okio.Buffer
import okio.BufferedSink
import okio.Okio
import shark.GcRoot.Debugger
import shark.GcRoot.Finalizing
import shark.GcRoot.InternedString
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.JniLocal
import shark.GcRoot.JniMonitor
import shark.GcRoot.MonitorUsed
import shark.GcRoot.NativeStack
import shark.GcRoot.ReferenceCleanup
import shark.GcRoot.StickyClass
import shark.GcRoot.ThreadBlock
import shark.GcRoot.ThreadObject
import shark.GcRoot.Unknown
import shark.GcRoot.Unreachable
import shark.GcRoot.VmInternal
import shark.HprofRecord.HeapDumpEndRecord
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.HeapDumpInfoRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StackFrameRecord
import shark.HprofRecord.StackTraceRecord
import shark.HprofRecord.StringRecord
import shark.HprofRecord.UnloadClassRecord
import shark.HprofRecordTag.CLASS_DUMP
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
import shark.HprofRecordTag.STACK_TRACE
import shark.HprofRecordTag.STRING_IN_UTF8
import shark.HprofRecordTag.UNLOAD_CLASS
import shark.HprofWriter.Companion.openWriterFor
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.CHAR
import shark.PrimitiveType.DOUBLE
import shark.PrimitiveType.FLOAT
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ByteHolder
import shark.ValueHolder.CharHolder
import shark.ValueHolder.DoubleHolder
import shark.ValueHolder.FloatHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.ValueHolder.ShortHolder

/**
 * Generates Hprof files.
 *
 * Call [openWriterFor] to obtain a new instance.
 *
 * Call [write] to add records and [close] when you're done.
 */
class HprofWriter private constructor(
  private val sink: BufferedSink,
  val hprofHeader: HprofHeader
) : Closeable {

  private val workBuffer = Buffer()

  /**
   * Appends a [HprofRecord] to the heap dump. If [record] is a [HprofRecord.HeapDumpRecord] then
   * it will not be written to an in memory buffer and written to file only when the next a record
   * that is not a [HprofRecord.HeapDumpRecord] is written or when [close] is called.
   */
  fun write(record: HprofRecord) {
    sink.write(record)
  }

  /**
   * Helper method for creating a [ByteArray] for [InstanceDumpRecord.fieldValues] from a
   * list of [ValueHolder].
   */
  fun valuesToBytes(values: List<ValueHolder>): ByteArray {
    val valuesBuffer = Buffer()
    values.forEach { value ->
      valuesBuffer.writeValue(value)
    }
    return valuesBuffer.readByteArray()
  }

  /**
   * Flushes to disk all [HprofRecord.HeapDumpRecord] that are currently written to the in memory
   * buffer, then closes the file.
   */
  override fun close() {
    sink.flushHeapBuffer()
    sink.close()
  }

  private fun BufferedSink.writeValue(wrapper: ValueHolder) {
    when (wrapper) {
      is ReferenceHolder -> writeId(wrapper.value)
      is BooleanHolder -> writeBoolean(wrapper.value)
      is CharHolder -> write(charArrayOf(wrapper.value))
      is FloatHolder -> writeFloat(wrapper.value)
      is DoubleHolder -> writeDouble(wrapper.value)
      is ByteHolder -> writeByte(wrapper.value.toInt())
      is ShortHolder -> writeShort(wrapper.value.toInt())
      is IntHolder -> writeInt(wrapper.value)
      is LongHolder -> writeLong(wrapper.value)
    }
  }

  private fun BufferedSink.write(record: HprofRecord) {
    when (record) {
      is StringRecord -> {
        writeNonHeapRecord(STRING_IN_UTF8.tag) {
          writeId(record.id)
          writeUtf8(record.string)
        }
      }
      is LoadClassRecord -> {
        writeNonHeapRecord(LOAD_CLASS.tag) {
          writeInt(record.classSerialNumber)
          writeId(record.id)
          writeInt(record.stackTraceSerialNumber)
          writeId(record.classNameStringId)
        }
      }
      is UnloadClassRecord -> {
        writeNonHeapRecord(UNLOAD_CLASS.tag) {
          writeInt(record.classSerialNumber)
        }
      }
      is StackTraceRecord -> {
        writeNonHeapRecord(STACK_TRACE.tag) {
          writeInt(record.stackTraceSerialNumber)
          writeInt(record.threadSerialNumber)
          writeInt(record.stackFrameIds.size)
          writeIdArray(record.stackFrameIds)
        }
      }
      is GcRootRecord -> {
        with(workBuffer) {
          when (val gcRoot = record.gcRoot) {
            is Unknown -> {
              writeByte(ROOT_UNKNOWN.tag)
              writeId(gcRoot.id)
            }
            is JniGlobal -> {
              writeByte(
                ROOT_JNI_GLOBAL.tag
              )
              writeId(gcRoot.id)
              writeId(gcRoot.jniGlobalRefId)
            }
            is JniLocal -> {
              writeByte(ROOT_JNI_LOCAL.tag)
              writeId(gcRoot.id)
              writeInt(gcRoot.threadSerialNumber)
              writeInt(gcRoot.frameNumber)
            }
            is JavaFrame -> {
              writeByte(ROOT_JAVA_FRAME.tag)
              writeId(gcRoot.id)
              writeInt(gcRoot.threadSerialNumber)
              writeInt(gcRoot.frameNumber)
            }
            is NativeStack -> {
              writeByte(ROOT_NATIVE_STACK.tag)
              writeId(gcRoot.id)
              writeInt(gcRoot.threadSerialNumber)
            }
            is StickyClass -> {
              writeByte(ROOT_STICKY_CLASS.tag)
              writeId(gcRoot.id)
            }
            is ThreadBlock -> {
              writeByte(ROOT_THREAD_BLOCK.tag)
              writeId(gcRoot.id)
              writeInt(gcRoot.threadSerialNumber)
            }
            is MonitorUsed -> {
              writeByte(ROOT_MONITOR_USED.tag)
              writeId(gcRoot.id)
            }
            is ThreadObject -> {
              writeByte(ROOT_THREAD_OBJECT.tag)
              writeId(gcRoot.id)
              writeInt(gcRoot.threadSerialNumber)
              writeInt(gcRoot.stackTraceSerialNumber)
            }
            is ReferenceCleanup -> {
              writeByte(ROOT_REFERENCE_CLEANUP.tag)
              writeId(gcRoot.id)
            }
            is VmInternal -> {
              writeByte(ROOT_VM_INTERNAL.tag)
              writeId(gcRoot.id)
            }
            is JniMonitor -> {
              writeByte(ROOT_JNI_MONITOR.tag)
              writeId(gcRoot.id)
              writeInt(gcRoot.stackTraceSerialNumber)
              writeInt(gcRoot.stackDepth)
            }
            is InternedString -> {
              writeByte(ROOT_INTERNED_STRING.tag)
              writeId(gcRoot.id)
            }
            is Finalizing -> {
              writeByte(ROOT_FINALIZING.tag)
              writeId(gcRoot.id)
            }
            is Debugger -> {
              writeByte(ROOT_DEBUGGER.tag)
              writeId(gcRoot.id)
            }
            is Unreachable -> {
              writeByte(ROOT_UNREACHABLE.tag)
              writeId(gcRoot.id)
            }
          }
        }
      }
      is ClassDumpRecord -> {
        with(workBuffer) {
          writeByte(CLASS_DUMP.tag)
          writeId(record.id)
          writeInt(record.stackTraceSerialNumber)
          writeId(record.superclassId)
          writeId(record.classLoaderId)
          writeId(record.signersId)
          writeId(record.protectionDomainId)
          // reserved
          writeId(0)
          // reserved
          writeId(0)
          writeInt(record.instanceSize)
          // Not writing anything in the constant pool
          val constantPoolCount = 0
          writeShort(constantPoolCount)
          writeShort(record.staticFields.size)
          record.staticFields.forEach { field ->
            writeId(field.nameStringId)
            writeByte(field.type)
            writeValue(field.value)
          }
          writeShort(record.fields.size)
          record.fields.forEach { field ->
            writeId(field.nameStringId)
            writeByte(field.type)
          }
        }
      }
      is InstanceDumpRecord -> {
        with(workBuffer) {
          writeByte(INSTANCE_DUMP.tag)
          writeId(record.id)
          writeInt(record.stackTraceSerialNumber)
          writeId(record.classId)
          writeInt(record.fieldValues.size)
          write(record.fieldValues)
        }
      }
      is ObjectArrayDumpRecord -> {
        with(workBuffer) {
          writeByte(OBJECT_ARRAY_DUMP.tag)
          writeId(record.id)
          writeInt(record.stackTraceSerialNumber)
          writeInt(record.elementIds.size)
          writeId(record.arrayClassId)
          writeIdArray(record.elementIds)
        }
      }
      is PrimitiveArrayDumpRecord -> {
        with(workBuffer) {
          writeByte(PRIMITIVE_ARRAY_DUMP.tag)
          writeId(record.id)
          writeInt(record.stackTraceSerialNumber)

          when (record) {
            is BooleanArrayDump -> {
              writeInt(record.array.size)
              writeByte(BOOLEAN.hprofType)
              write(record.array)
            }
            is CharArrayDump -> {
              writeInt(record.array.size)
              writeByte(CHAR.hprofType)
              write(record.array)
            }
            is FloatArrayDump -> {
              writeInt(record.array.size)
              writeByte(FLOAT.hprofType)
              write(record.array)
            }
            is DoubleArrayDump -> {
              writeInt(record.array.size)
              writeByte(DOUBLE.hprofType)
              write(record.array)
            }
            is ByteArrayDump -> {
              writeInt(record.array.size)
              writeByte(BYTE.hprofType)
              write(record.array)
            }
            is ShortArrayDump -> {
              writeInt(record.array.size)
              writeByte(SHORT.hprofType)
              write(record.array)
            }
            is IntArrayDump -> {
              writeInt(record.array.size)
              writeByte(INT.hprofType)
              write(record.array)
            }
            is LongArrayDump -> {
              writeInt(record.array.size)
              writeByte(LONG.hprofType)
              write(record.array)
            }
          }
        }
      }
      is HeapDumpInfoRecord -> {
        with(workBuffer) {
          writeByte(HEAP_DUMP_INFO.tag)
          writeInt(record.heapId)
          writeId(record.heapNameStringId)
        }
      }
      is HeapDumpEndRecord -> {
        throw IllegalArgumentException("HprofWriter automatically emits HeapDumpEndRecord")
      }

      is StackFrameRecord -> {
        SharkLog.d { "Ignoring writing of StackFrameRecord, not supported yet." }
      }
    }
  }

  private fun BufferedSink.writeDouble(value: Double) {
    writeLong(value.toBits())
  }

  private fun BufferedSink.writeFloat(value: Float) {
    writeInt(value.toBits())
  }

  private fun BufferedSink.writeBoolean(value: Boolean) {
    writeByte(if (value) 1 else 0)
  }

  private fun BufferedSink.writeIdArray(array: LongArray) {
    array.forEach { writeId(it) }
  }

  private fun BufferedSink.write(array: BooleanArray) {
    array.forEach { writeByte(if (it) 1 else 0) }
  }

  private fun BufferedSink.write(array: CharArray) {
    writeString(String(array), Charsets.UTF_16BE)
  }

  private fun BufferedSink.write(array: FloatArray) {
    array.forEach { writeFloat(it) }
  }

  private fun BufferedSink.write(array: DoubleArray) {
    array.forEach { writeDouble(it) }
  }

  private fun BufferedSink.write(array: ShortArray) {
    array.forEach { writeShort(it.toInt()) }
  }

  private fun BufferedSink.write(array: IntArray) {
    array.forEach { writeInt(it) }
  }

  private fun BufferedSink.write(array: LongArray) {
    array.forEach { writeLong(it) }
  }

  private fun BufferedSink.writeNonHeapRecord(
    tag: Int,
    block: BufferedSink.() -> Unit
  ) {
    flushHeapBuffer()
    workBuffer.block()
    writeTagHeader(tag, workBuffer.size())
    writeAll(workBuffer)
  }

  private fun BufferedSink.flushHeapBuffer() {
    if (workBuffer.size() > 0) {
      writeTagHeader(HprofRecordTag.HEAP_DUMP.tag, workBuffer.size())
      writeAll(workBuffer)
      writeTagHeader(HprofRecordTag.HEAP_DUMP_END.tag, 0)
    }
  }

  private fun BufferedSink.writeTagHeader(
    tag: Int,
    length: Long
  ) {
    writeByte(tag)
    // number of microseconds since the time stamp in the header
    writeInt(0)
    writeInt(length.toInt())
  }

  private fun BufferedSink.writeId(id: Long) {
    when (hprofHeader.identifierByteSize) {
      1 -> writeByte(id.toInt())
      2 -> writeShort(id.toInt())
      4 -> writeInt(id.toInt())
      8 -> writeLong(id)
      else -> throw IllegalArgumentException("ID Length must be 1, 2, 4, or 8")
    }
  }

  companion object {

    fun openWriterFor(
      hprofFile: File,
      hprofHeader: HprofHeader = HprofHeader()
    ): HprofWriter {
      return openWriterFor(Okio.buffer(Okio.sink(hprofFile.outputStream())), hprofHeader)
    }

    fun openWriterFor(
      hprofSink: BufferedSink,
      hprofHeader: HprofHeader = HprofHeader()
    ): HprofWriter {
      hprofSink.writeUtf8(hprofHeader.version.versionString)
      hprofSink.writeByte(0)
      hprofSink.writeInt(hprofHeader.identifierByteSize)
      hprofSink.writeLong(hprofHeader.heapDumpTimestamp)
      return HprofWriter(hprofSink, hprofHeader)
    }
  }
}
