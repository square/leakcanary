package leakcanary

import leakcanary.GcRoot.Debugger
import leakcanary.GcRoot.Finalizing
import leakcanary.GcRoot.InternedString
import leakcanary.GcRoot.JavaFrame
import leakcanary.GcRoot.JniGlobal
import leakcanary.GcRoot.JniLocal
import leakcanary.GcRoot.JniMonitor
import leakcanary.GcRoot.MonitorUsed
import leakcanary.GcRoot.NativeStack
import leakcanary.GcRoot.ReferenceCleanup
import leakcanary.GcRoot.StickyClass
import leakcanary.GcRoot.ThreadBlock
import leakcanary.GcRoot.ThreadObject
import leakcanary.GcRoot.Unknown
import leakcanary.GcRoot.Unreachable
import leakcanary.GcRoot.VmInternal
import leakcanary.HeapValue.BooleanValue
import leakcanary.HeapValue.ByteValue
import leakcanary.HeapValue.CharValue
import leakcanary.HeapValue.DoubleValue
import leakcanary.HeapValue.FloatValue
import leakcanary.HeapValue.IntValue
import leakcanary.HeapValue.LongValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HeapValue.ShortValue
import leakcanary.Record.HeapDumpEndRecord
import leakcanary.Record.HeapDumpRecord.GcRootRecord
import leakcanary.Record.HeapDumpRecord.HeapDumpInfoRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import leakcanary.Record.LoadClassRecord
import leakcanary.Record.StackTraceRecord
import leakcanary.Record.StringRecord
import okio.Buffer
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.Closeable
import java.io.File

class HprofWriter private constructor(
  private val sink: BufferedSink,
  val idSize: Int
) : Closeable {

  private val workBuffer = Buffer()

  fun write(record: Record) {
    sink.write(record)
  }

  private fun BufferedSink.write(record: Record) {
    when (record) {
      is StringRecord -> {
        writeNonHeapRecord(HprofParser.STRING_IN_UTF8) {
          writeId(record.id)
          writeUtf8(record.string)
        }
      }
      is LoadClassRecord -> {
        writeNonHeapRecord(HprofParser.LOAD_CLASS) {
          writeInt(record.classSerialNumber)
          writeId(record.id)
          writeInt(record.stackTraceSerialNumber)
          writeId(record.classNameStringId)
        }
      }
      is StackTraceRecord -> {
        writeNonHeapRecord(HprofParser.STACK_TRACE) {
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
              writeByte(HprofParser.ROOT_UNKNOWN)
              writeId(gcRoot.id)
            }
            is JniGlobal -> {
              writeByte(
                  HprofParser.ROOT_JNI_GLOBAL
              )
              writeId(gcRoot.id)
              writeId(gcRoot.jniGlobalRefId)
            }
            is JniLocal -> {
              writeByte(HprofParser.ROOT_JNI_LOCAL)
              writeId(gcRoot.id)
              writeInt(gcRoot.threadSerialNumber)
              writeInt(gcRoot.frameNumber)
            }
            is JavaFrame -> {
              writeByte(HprofParser.ROOT_JAVA_FRAME)
              writeId(gcRoot.id)
              writeInt(gcRoot.threadSerialNumber)
              writeInt(gcRoot.frameNumber)
            }
            is NativeStack -> {
              writeByte(HprofParser.ROOT_NATIVE_STACK)
              writeId(gcRoot.id)
              writeInt(gcRoot.threadSerialNumber)
            }
            is StickyClass -> {
              writeByte(HprofParser.ROOT_STICKY_CLASS)
              writeId(gcRoot.id)
            }
            is ThreadBlock -> {
              writeByte(HprofParser.ROOT_THREAD_BLOCK)
              writeId(gcRoot.id)
              writeInt(gcRoot.threadSerialNumber)
            }
            is MonitorUsed -> {
              writeByte(HprofParser.ROOT_MONITOR_USED)
              writeId(gcRoot.id)
            }
            is ThreadObject -> {
              writeByte(HprofParser.ROOT_THREAD_OBJECT)
              writeId(gcRoot.id)
              writeInt(gcRoot.threadSerialNumber)
              writeInt(gcRoot.stackTraceSerialNumber)
            }
            is ReferenceCleanup -> {
              writeByte(HprofParser.ROOT_REFERENCE_CLEANUP)
              writeId(gcRoot.id)
            }
            is VmInternal -> {
              writeByte(HprofParser.ROOT_VM_INTERNAL)
              writeId(gcRoot.id)
            }
            is JniMonitor -> {
              writeByte(HprofParser.ROOT_JNI_MONITOR)
              writeId(gcRoot.id)
              writeInt(gcRoot.stackTraceSerialNumber)
              writeInt(gcRoot.stackDepth)
            }
            is InternedString -> {
              writeByte(HprofParser.ROOT_INTERNED_STRING)
              writeId(gcRoot.id)
            }
            is Finalizing -> {
              writeByte(HprofParser.ROOT_FINALIZING)
              writeId(gcRoot.id)
            }
            is Debugger -> {
              writeByte(HprofParser.ROOT_DEBUGGER)
              writeId(gcRoot.id)
            }
            is Unreachable -> {
              writeByte(HprofParser.ROOT_UNREACHABLE)
              writeId(gcRoot.id)
            }
          }
        }
      }
      is ClassDumpRecord -> {
        with(workBuffer) {
          writeByte(HprofParser.CLASS_DUMP)
          writeId(record.id)
          writeInt(record.stackTraceSerialNumber)
          writeId(record.superClassId)
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
          writeByte(HprofParser.INSTANCE_DUMP)
          writeId(record.id)
          writeInt(record.stackTraceSerialNumber)
          writeId(record.classId)
          writeInt(record.fieldValues.size)
          write(record.fieldValues)
        }
      }
      is ObjectArrayDumpRecord -> {
        with(workBuffer) {
          writeByte(HprofParser.OBJECT_ARRAY_DUMP)
          writeId(record.id)
          writeInt(record.stackTraceSerialNumber)
          writeInt(record.elementIds.size)
          writeId(record.arrayClassId)
          writeIdArray(record.elementIds)
        }
      }
      is PrimitiveArrayDumpRecord -> {
        with(workBuffer) {
          writeByte(HprofParser.PRIMITIVE_ARRAY_DUMP)
          writeId(record.id)
          writeInt(record.stackTraceSerialNumber)

          when (record) {
            is BooleanArrayDump -> {
              writeInt(record.array.size)
              writeByte(HprofReader.BOOLEAN_TYPE)
              write(record.array)
            }
            is CharArrayDump -> {
              writeInt(record.array.size)
              writeByte(HprofReader.CHAR_TYPE)
              write(record.array)
            }
            is FloatArrayDump -> {
              writeInt(record.array.size)
              writeByte(HprofReader.FLOAT_TYPE)
              write(record.array)
            }
            is DoubleArrayDump -> {
              writeInt(record.array.size)
              writeByte(HprofReader.DOUBLE_TYPE)
              write(record.array)
            }
            is ByteArrayDump -> {
              writeInt(record.array.size)
              writeByte(HprofReader.BYTE_TYPE)
              write(record.array)
            }
            is ShortArrayDump -> {
              writeInt(record.array.size)
              writeByte(HprofReader.SHORT_TYPE)
              write(record.array)
            }
            is IntArrayDump -> {
              writeInt(record.array.size)
              writeByte(HprofReader.INT_TYPE)
              write(record.array)
            }
            is LongArrayDump -> {
              writeInt(record.array.size)
              writeByte(HprofReader.LONG_TYPE)
              write(record.array)
            }
          }
        }
      }
      is HeapDumpInfoRecord -> {
        with(workBuffer) {
          writeByte(HprofParser.HEAP_DUMP_INFO)
          writeInt(record.heapId)
          writeId(record.heapNameStringId)
        }
      }
      is HeapDumpEndRecord -> {
        throw IllegalArgumentException("HprofWriter automatically emits HeapDumpEndRecord")
      }
    }
  }

  fun BufferedSink.writeValue(wrapper: HeapValue) {
    when (wrapper) {
      is ObjectReference -> writeId(wrapper.value)
      is BooleanValue -> writeBoolean(wrapper.value)
      is CharValue -> write(charArrayOf(wrapper.value))
      is FloatValue -> writeFloat(wrapper.value)
      is DoubleValue -> writeDouble(wrapper.value)
      is ByteValue -> writeByte(wrapper.value.toInt())
      is ShortValue -> writeShort(wrapper.value.toInt())
      is IntValue -> writeInt(wrapper.value)
      is LongValue -> writeLong(wrapper.value)
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
    writeTagHeader(tag, workBuffer.size)
    writeAll(workBuffer)
  }

  private fun BufferedSink.flushHeapBuffer() {
    if (workBuffer.size > 0) {
      writeTagHeader(HprofParser.HEAP_DUMP, workBuffer.size)
      writeAll(workBuffer)
      writeTagHeader(HprofParser.HEAP_DUMP_END, 0)
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
    when (idSize) {
      1 -> writeByte(id.toInt())
      2 -> writeShort(id.toInt())
      4 -> writeInt(id.toInt())
      8 -> writeLong(id)
      else -> throw IllegalArgumentException("ID Length must be 1, 2, 4, or 8")
    }
  }

  override fun close() {
    sink.flushHeapBuffer()
    sink.close()
  }

  companion object {
    fun open(
      hprofFile: File,
      idSize: Int = 4
    ): HprofWriter {

      val sink = hprofFile.outputStream()
          .sink()
          .buffer()

      val hprofVersion = "JAVA PROFILE 1.0.3"
      sink.writeUtf8(hprofVersion)
      sink.writeByte(0)
      sink.writeInt(idSize)
      val heapDumpTimestamp = System.currentTimeMillis()
      sink.writeLong(heapDumpTimestamp)
      return HprofWriter(sink, idSize)
    }
  }
}