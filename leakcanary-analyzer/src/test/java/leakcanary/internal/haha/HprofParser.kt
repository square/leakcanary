package leakcanary.internal.haha

import leakcanary.internal.haha.GcRoot.Debugger
import leakcanary.internal.haha.GcRoot.Finalizing
import leakcanary.internal.haha.GcRoot.InternedString
import leakcanary.internal.haha.GcRoot.JavaFrame
import leakcanary.internal.haha.GcRoot.JniGlobal
import leakcanary.internal.haha.GcRoot.JniLocal
import leakcanary.internal.haha.GcRoot.JniMonitor
import leakcanary.internal.haha.GcRoot.MonitorUsed
import leakcanary.internal.haha.GcRoot.NativeStack
import leakcanary.internal.haha.GcRoot.ReferenceCleanup
import leakcanary.internal.haha.GcRoot.StickyClass
import leakcanary.internal.haha.GcRoot.ThreadBlock
import leakcanary.internal.haha.GcRoot.ThreadObject
import leakcanary.internal.haha.GcRoot.Unknown
import leakcanary.internal.haha.GcRoot.Unreachable
import leakcanary.internal.haha.GcRoot.VmInternal
import leakcanary.internal.haha.HprofParser.Value.BooleanValue
import leakcanary.internal.haha.HprofParser.Value.ByteValue
import leakcanary.internal.haha.HprofParser.Value.CharValue
import leakcanary.internal.haha.HprofParser.Value.DoubleValue
import leakcanary.internal.haha.HprofParser.Value.FloatValue
import leakcanary.internal.haha.HprofParser.Value.IntValue
import leakcanary.internal.haha.HprofParser.Value.LongValue
import leakcanary.internal.haha.HprofParser.Value.ObjectReference
import leakcanary.internal.haha.HprofParser.Value.ShortValue
import leakcanary.internal.haha.Record.HeapDumpRecord.ClassDumpRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.ClassDumpRecord.FieldRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.ClassDumpRecord.StaticFieldRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.GcRootRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.HeapDumpInfoRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.InstanceDumpRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.ObjectArrayDumpRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.PrimitiveArrayDumpRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import leakcanary.internal.haha.Record.HeapDumpRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import leakcanary.internal.haha.Record.HeapDumpRecord.PrimitiveArrayDumpRecord.CharArrayDump
import leakcanary.internal.haha.Record.HeapDumpRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import leakcanary.internal.haha.Record.HeapDumpRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import leakcanary.internal.haha.Record.HeapDumpRecord.PrimitiveArrayDumpRecord.IntArrayDump
import leakcanary.internal.haha.Record.HeapDumpRecord.PrimitiveArrayDumpRecord.LongArrayDump
import leakcanary.internal.haha.Record.HeapDumpRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import leakcanary.internal.haha.Record.LoadClassRecord
import leakcanary.internal.haha.Record.StringRecord
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Not thread safe, should be used from a single thread.
 */
class HprofParser private constructor(
  private val channel: FileChannel,
  private var source: BufferedSource,
  private val startPosition: Long,
  private val idSize: Int
) : Closeable {

  class RecordCallbacks {
    private val callbacks = mutableMapOf<Class<out Record>, Any>()

    fun <T : Record> on(
      recordClass: Class<T>,
      callback: (T) -> Unit
    ): RecordCallbacks {
      callbacks[recordClass] = callback
      return this
    }

    fun <T : Record> get(recordClass: Class<T>): ((T) -> Unit)? {
      @Suppress("UNCHECKED_CAST")
      return callbacks[recordClass] as ((T) -> Unit)?
    }

    inline fun <reified T : Record> get(): ((T) -> Unit)? {
      return get(T::class.java)
    }
  }

  private val typeSizes = mapOf(
      // object
      OBJECT_TYPE to idSize,
      BOOLEAN_TYPE to BOOLEAN_SIZE,
      CHAR_TYPE to CHAR_SIZE,
      FLOAT_TYPE to FLOAT_SIZE,
      DOUBLE_TYPE to DOUBLE_SIZE,
      BYTE_TYPE to BYTE_SIZE,
      SHORT_TYPE to SHORT_SIZE,
      INT_TYPE to INT_SIZE,
      LONG_TYPE to LONG_SIZE
  )

  sealed class Value {
    data class ObjectReference(val value: Long) : Value()
    data class BooleanValue(val value: Boolean) : Value()
    data class CharValue(val value: Char) : Value()
    data class FloatValue(val value: Float) : Value()
    data class DoubleValue(val value: Double) : Value()
    data class ByteValue(val value: Byte) : Value()
    data class ShortValue(val value: Short) : Value()
    data class IntValue(val value: Int) : Value()
    data class LongValue(val value: Long) : Value()
  }

  private fun readValue(type: Int): Value {
    return when (type) {
      OBJECT_TYPE -> ObjectReference(readId())
      BOOLEAN_TYPE -> BooleanValue(readBoolean())
      CHAR_TYPE -> CharValue(readChar())
      FLOAT_TYPE -> FloatValue(readFloat())
      DOUBLE_TYPE -> DoubleValue(readDouble())
      BYTE_TYPE -> ByteValue(readByte())
      SHORT_TYPE -> ShortValue(readShort())
      INT_TYPE -> IntValue(readInt())
      LONG_TYPE -> LongValue(readLong())
      else -> throw IllegalStateException("Unknown type $type")
    }
  }

  private fun typeSize(type: Int): Int {
    return typeSizes.getValue(type)
  }

  private var position: Long = startPosition

  private fun moveTo(newPosition: Long) {
    if (position == newPosition) {
      return
    }
    source.buffer.clear()
    channel.position(newPosition)
    this.position = newPosition
  }

  private fun readShort(): Short {
    position += SHORT_SIZE
    return source.readShort()
  }

  private fun readInt(): Int {
    position += INT_SIZE
    return source.readInt()
  }

  private fun readIdArray(arrayLength: Int): LongArray {
    // TODO Read the array at once
    val array = LongArray(arrayLength)
    for (i in 0 until arrayLength) {
      array[i] = readId()
    }
    return array
  }

  private fun readBooleanArray(arrayLength: Int): BooleanArray {
    // TODO Read the array at once
    val array = BooleanArray(arrayLength)
    readByteArray(BOOLEAN_SIZE * arrayLength).forEachIndexed { index, byte ->
      array[index] = byte != 0.toByte()
    }
    return array
  }

  private fun readCharArray(arrayLength: Int): CharArray {
    // TODO Avoid creating a byte array
    return ByteBuffer.wrap(readByteArray(CHAR_SIZE * arrayLength))
        .asCharBuffer()
        .array()
  }

  private fun readFloatArray(arrayLength: Int): FloatArray {
    // TODO Avoid creating a byte array
    return ByteBuffer.wrap(readByteArray(FLOAT_SIZE * arrayLength))
        .asFloatBuffer()
        .array()
  }

  private fun readDoubleArray(arrayLength: Int): DoubleArray {
    // TODO Avoid creating a byte array
    return ByteBuffer.wrap(readByteArray(DOUBLE_SIZE * arrayLength))
        .asDoubleBuffer()
        .array()
  }

  private fun readShortArray(arrayLength: Int): ShortArray {
    // TODO Avoid creating a byte array
    return ByteBuffer.wrap(readByteArray(SHORT_SIZE * arrayLength))
        .asShortBuffer()
        .array()
  }

  private fun readIntArray(arrayLength: Int): IntArray {
    // TODO Avoid creating a byte array
    return ByteBuffer.wrap(readByteArray(INT_SIZE * arrayLength))
        .asIntBuffer()
        .array()
  }

  private fun readLongArray(arrayLength: Int): LongArray {
    // TODO Avoid creating a byte array
    return ByteBuffer.wrap(readByteArray(LONG_SIZE * arrayLength))
        .asLongBuffer()
        .array()
  }

  private fun skipShort() {
    skip(SHORT_SIZE)
  }

  private fun skipInt() {
    skip(INT_SIZE)
  }

  private fun skipLong() {
    skip(LONG_SIZE)
  }

  private fun readLong(): Long {
    position += LONG_SIZE
    return source.readLong()
  }

  private fun exhausted() = source.exhausted()

  private fun skip(byteCount: Long) {
    position += byteCount
    return source.skip(byteCount)
  }

  private fun readByte(): Byte {
    position += BYTE_SIZE
    return source.readByte()
  }

  private fun readBoolean(): Boolean {
    position += BOOLEAN_SIZE
    return source.readByte() != 0.toByte()
  }

  private fun readByteArray(byteCount: Int): ByteArray {
    position += byteCount
    return source.readByteArray(byteCount.toLong())
  }

  private fun readChar(): Char {
    // TODO Avoid creating a byte array
    return ByteBuffer.wrap(readByteArray(CHAR_SIZE))
        .char
  }

  private fun readFloat(): Float {
    return Float.fromBits(readInt())
  }

  private fun readDouble(): Double {
    return Double.fromBits(readLong())
  }

  private fun readId(): Long {
    // As long as we don't interpret IDs, reading signed values here is fine.
    return when (idSize) {
      1 -> readByte().toLong()
      2 -> readShort().toLong()
      4 -> readInt().toLong()
      8 -> readLong()
      else -> throw IllegalArgumentException("ID Length must be 1, 2, 4, or 8")
    }
  }

  private fun readUtf8(byteCount: Long): String {
    position += byteCount
    return source.readUtf8(byteCount)
  }

  private fun readUnsignedInt(): Long {
    return readInt().toLong() and INT_MASK
  }

  private fun readUnsignedByte(): Int {
    return readByte().toInt() and BYTE_MASK
  }

  private fun readUnsignedShort(): Int {
    return readShort().toInt() and 0xFFFF
  }

  private fun skip(byteCount: Int) {
    position += byteCount
    return source.skip(byteCount.toLong())
  }

  override fun close() {
    source.close()
  }

  fun scan(callbacks: RecordCallbacks) {
    if (!source.isOpen) {
      throw IllegalStateException("Source closed")
    }

    moveTo(startPosition)

    // heap dump timestamp
    skipLong()

    var heapId = 0
    while (!exhausted()) {
      // type of the record
      val tag = readUnsignedByte()

      // number of microseconds since the time stamp in the header
      skipInt()

      // number of bytes that follow and belong to this record
      val length = readUnsignedInt()

      when (tag) {
        STRING_IN_UTF8 -> {
          val callback = callbacks.get<StringRecord>()
          if (callback != null) {
            val id = readId()
            val string = readUtf8(length - idSize)
            callback(StringRecord(id, string))
          } else {
            skip(length)
          }
        }
        LOAD_CLASS -> {
          val callback = callbacks.get<LoadClassRecord>()
          if (callback != null) {
            callback(
                LoadClassRecord(
                    classSerialNumber = readInt(),
                    id = readId(),
                    stackTraceSerialNumber = readInt(),
                    classNameStringId = readId()
                )
            )
          } else {
            skip(length)
          }
        }
        HEAP_DUMP, HEAP_DUMP_SEGMENT -> {
          val heapDumpStart = position
          var previousTag = 0
          while (position - heapDumpStart < length) {
            val heapDumpTag = readUnsignedByte()

            when (heapDumpTag) {
              ROOT_UNKNOWN -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = Unknown(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_JNI_GLOBAL -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = JniGlobal(id = readId(), jniGlobalRefId = readId())
                      )
                  )
                } else {
                  skip(idSize + idSize)
                }
              }

              ROOT_JNI_LOCAL -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = JniLocal(
                              id = readId(), threadSerialNumber = readInt(), frameNumber = readInt()
                          )
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE + INT_SIZE)
                }
              }

              ROOT_JAVA_FRAME -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = JavaFrame(
                              id = readId(), threadSerialNumber = readInt(), frameNumber = readInt()
                          )
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE + INT_SIZE)
                }
              }

              ROOT_NATIVE_STACK -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = NativeStack(id = readId(), threadSerialNumber = readInt())
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE)
                }
              }

              ROOT_STICKY_CLASS -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = StickyClass(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              // An object that was referenced from an active thread block.
              ROOT_THREAD_BLOCK -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = ThreadBlock(id = readId(), threadSerialNumber = readInt())
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE)
                }
              }

              ROOT_MONITOR_USED -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = MonitorUsed(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_THREAD_OBJECT -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = ThreadObject(
                              id = readId(),
                              threadSerialNumber = readInt(),
                              stackTraceSerialNumber = readInt()
                          )
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE + INT_SIZE)
                }
              }

              CLASS_DUMP -> {
                val callback = callbacks.get<ClassDumpRecord>()
                if (callback != null) {
                  val id = readId()
                  // stack trace serial number
                  val stackTraceSerialNumber = readInt()
                  val superClassId = readId()
                  // class loader object ID
                  val classLoaderId = readId()
                  // signers object ID
                  val signersId = readId()
                  // protection domain object ID
                  val protectionDomainId = readId()
                  // reserved
                  readId()
                  // reserved
                  readId()

                  // instance size (in bytes)
                  // Useful to compute retained size
                  val instanceSize = readInt()

                  // Skip over the constant pool
                  val constantPoolCount = readUnsignedShort()
                  for (i in 0 until constantPoolCount) {
                    // constant pool index
                    skipShort()
                    skip(typeSize(readUnsignedByte()))
                  }

                  val staticFields = mutableListOf<StaticFieldRecord>()
                  val staticFieldCount = readUnsignedShort()
                  for (i in 0 until staticFieldCount) {

                    val nameStringId = readId()
                    val type = readUnsignedByte()
                    val value = readValue(type)

                    staticFields.add(
                        StaticFieldRecord(
                            nameStringId = nameStringId,
                            type = type,
                            value = value
                        )
                    )
                  }

                  val fields = mutableListOf<FieldRecord>()
                  val fieldCount = readUnsignedShort()
                  for (i in 0 until fieldCount) {
                    fields.add(FieldRecord(nameStringId = readId(), type = readUnsignedByte()))
                  }

                  callback(
                      ClassDumpRecord(
                          heapId = heapId,
                          id = id,
                          stackTraceSerialNumber = stackTraceSerialNumber,
                          superClassId = superClassId,
                          classLoaderId = classLoaderId,
                          signersId = signersId,
                          protectionDomainId = protectionDomainId,
                          instanceSize = instanceSize,
                          staticFields = staticFields,
                          fields = fields
                      )
                  )
                } else {
                  skip(
                      idSize + INT_SIZE + idSize + idSize + idSize + idSize + idSize + idSize + INT_SIZE
                  )

                  // Skip over the constant pool
                  val constantPoolCount = readUnsignedShort()
                  for (i in 0 until constantPoolCount) {
                    // constant pool index
                    skipShort()
                    skip(typeSize(readUnsignedByte()))
                  }

                  val staticFieldCount = readUnsignedShort()

                  for (i in 0 until staticFieldCount) {
                    skip(idSize)
                    val type = readUnsignedByte()
                    skip(typeSize(type))
                  }

                  val fieldCount = readUnsignedShort()
                  skip(fieldCount * (idSize + BYTE_SIZE))
                }
              }

              INSTANCE_DUMP -> {
                val callback = callbacks.get<InstanceDumpRecord>()
                if (callback != null) {
                  val id = readId()
                  val stackTraceSerialNumber = readInt()
                  val classId = readId()
                  val remainingBytesInInstance = readInt()
                  val fieldValues = readByteArray(remainingBytesInInstance)

                  callback(
                      InstanceDumpRecord(
                          heapId = heapId,
                          id = id,
                          stackTraceSerialNumber = stackTraceSerialNumber,
                          classId = classId,
                          fieldValues = fieldValues
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE + idSize)
                  val remainingBytesInInstance = readInt()
                  skip(remainingBytesInInstance)
                }
              }

              OBJECT_ARRAY_DUMP -> {
                val callback = callbacks.get<ObjectArrayDumpRecord>()
                if (callback != null) {
                  val id = readId()
                  // stack trace serial number
                  val stackTraceSerialNumber = readInt()
                  val arrayLength = readInt()
                  val arrayClassId = readId()
                  val elementIds = readIdArray(arrayLength)
                  callback(
                      ObjectArrayDumpRecord(
                          heapId = heapId,
                          id = id,
                          stackTraceSerialNumber = stackTraceSerialNumber,
                          arrayClassId = arrayClassId,
                          elementIds = elementIds
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE)
                  val arrayLength = readInt()
                  skip(idSize + arrayLength * idSize)
                }
              }

              PRIMITIVE_ARRAY_DUMP -> {
                val callback = callbacks.get<PrimitiveArrayDumpRecord>()
                if (callback != null) {
                  val id = readId()
                  val stackTraceSerialNumber = readInt()
                  // length
                  val arrayLength = readInt()
                  val type = readUnsignedByte()

                  val primitiveArrayDumpRecord = when (type) {
                    BOOLEAN_TYPE -> BooleanArrayDump(
                        heapId,
                        id, stackTraceSerialNumber, readBooleanArray(arrayLength)
                    )
                    CHAR_TYPE -> CharArrayDump(
                        heapId,
                        id, stackTraceSerialNumber, readCharArray(arrayLength)
                    )
                    FLOAT_TYPE -> FloatArrayDump(
                        heapId,
                        id, stackTraceSerialNumber, readFloatArray(arrayLength)
                    )
                    DOUBLE_TYPE -> DoubleArrayDump(
                        heapId,
                        id, stackTraceSerialNumber, readDoubleArray(arrayLength)
                    )
                    BYTE_TYPE -> ByteArrayDump(
                        heapId,
                        id, stackTraceSerialNumber, readByteArray(arrayLength)
                    )
                    SHORT_TYPE -> ShortArrayDump(
                        heapId,
                        id, stackTraceSerialNumber, readShortArray(arrayLength)
                    )
                    INT_TYPE -> IntArrayDump(
                        heapId,
                        id, stackTraceSerialNumber, readIntArray(arrayLength)
                    )
                    LONG_TYPE -> LongArrayDump(
                        heapId,
                        id, stackTraceSerialNumber, readLongArray(arrayLength)
                    )
                    else -> throw IllegalStateException("Unexpected type $type")
                  }
                  callback(primitiveArrayDumpRecord)
                } else {
                  skip(idSize + INT_SIZE)
                  val arrayLength = readInt()
                  val type = readUnsignedByte()
                  skip(arrayLength * typeSize(type))
                }
              }

              PRIMITIVE_ARRAY_NODATA -> {
                throw UnsupportedOperationException(
                    "PRIMITIVE_ARRAY_NODATA cannot be parsed"
                )
              }

              HEAP_DUMP_INFO -> {
                heapId = readInt()
                val callback = callbacks.get<HeapDumpInfoRecord>()
                if (callback != null) {
                  val record =
                    HeapDumpInfoRecord(heapId = heapId, heapNameStringId = readId())
                  callback(record)
                } else {
                  skip(idSize)
                }

              }

              ROOT_INTERNED_STRING -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = InternedString(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_FINALIZING -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = Finalizing(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_DEBUGGER -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = Debugger(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_REFERENCE_CLEANUP -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = ReferenceCleanup(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_VM_INTERNAL -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = VmInternal(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }

              ROOT_JNI_MONITOR -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = JniMonitor(
                              id = readId(), stackTraceSerialNumber = readInt(),
                              stackDepth = readInt()
                          )
                      )
                  )
                } else {
                  skip(idSize + INT_SIZE + INT_SIZE)
                }
              }

              ROOT_UNREACHABLE -> {
                val callback = callbacks.get<GcRootRecord>()
                if (callback != null) {
                  callback(
                      GcRootRecord(
                          heapId = heapId,
                          gcRoot = Unreachable(id = readId())
                      )
                  )
                } else {
                  skip(idSize)
                }
              }
              else -> throw IllegalStateException(
                  "Unknown tag $heapDumpTag after $previousTag"
              )
            }
            previousTag = heapDumpTag
          }
          heapId = 0
        }
        else -> {
          skip(length)
        }
      }
    }
  }

  companion object {

    private const val BOOLEAN_SIZE = 1
    private const val CHAR_SIZE = 2
    private const val FLOAT_SIZE = 4
    private const val DOUBLE_SIZE = 8
    private const val BYTE_SIZE = 1
    private const val SHORT_SIZE = 2
    private const val INT_SIZE = 4
    private const val LONG_SIZE = 8

    const val OBJECT_TYPE = 2
    const val BOOLEAN_TYPE = 4
    const val CHAR_TYPE = 5
    const val FLOAT_TYPE = 6
    const val DOUBLE_TYPE = 7
    const val BYTE_TYPE = 8
    const val SHORT_TYPE = 9
    const val INT_TYPE = 10
    const val LONG_TYPE = 11

    const val INT_MASK = 0xffffffffL
    const val BYTE_MASK = 0xFF

    const val STRING_IN_UTF8 = 0x01
    const val LOAD_CLASS = 0x02
    const val UNLOAD_CLASS = 0x03
    const val STACK_FRAME = 0x04
    const val STACK_TRACE = 0x05
    const val ALLOC_SITES = 0x06
    const val HEAP_SUMMARY = 0x07
    const val START_THREAD = 0x0a
    const val END_THREAD = 0x0b
    const val HEAP_DUMP = 0x0c
    const val HEAP_DUMP_SEGMENT = 0x1c

    const val HEAP_DUMP_END = 0x2c

    const val CPU_SAMPLES = 0x0d

    const val CONTROL_SETTINGS = 0x0e

    const val ROOT_UNKNOWN = 0xff

    const val ROOT_JNI_GLOBAL = 0x01

    const val ROOT_JNI_LOCAL = 0x02

    const val ROOT_JAVA_FRAME = 0x03

    const val ROOT_NATIVE_STACK = 0x04

    const val ROOT_STICKY_CLASS = 0x05

    const val ROOT_THREAD_BLOCK = 0x06

    const val ROOT_MONITOR_USED = 0x07

    const val ROOT_THREAD_OBJECT = 0x08

    const val CLASS_DUMP = 0x20

    const val INSTANCE_DUMP = 0x21

    const val OBJECT_ARRAY_DUMP = 0x22

    const val PRIMITIVE_ARRAY_DUMP = 0x23

    /**
     * Android format addition
     *
     * Specifies information about which heap certain objects came from. When a sub-tag of this type
     * appears in a HPROF_HEAP_DUMP or HPROF_HEAP_DUMP_SEGMENT record, entries that follow it will
     * be associated with the specified heap.  The HEAP_DUMP_INFO data is reset at the end of the
     * HEAP_DUMP[_SEGMENT].  Multiple HEAP_DUMP_INFO entries may appear in a single
     * HEAP_DUMP[_SEGMENT].
     *
     * Format: u1: Tag value (0xFE) u4: heap ID ID: heap name string ID
     */
    const val HEAP_DUMP_INFO = 0xfe

    const val ROOT_INTERNED_STRING = 0x89

    const val ROOT_FINALIZING = 0x8a

    const val ROOT_DEBUGGER = 0x8b

    const val ROOT_REFERENCE_CLEANUP = 0x8c

    const val ROOT_VM_INTERNAL = 0x8d

    const val ROOT_JNI_MONITOR = 0x8e

    const val ROOT_UNREACHABLE = 0x90

    const val PRIMITIVE_ARRAY_NODATA = 0xc3

    fun open(heapDump: File): HprofParser {
      val inputStream = heapDump.inputStream()
      val channel = inputStream.channel
      val source = inputStream.source()
          .buffer()

      val endOfVersionString = source.indexOf(0)
      source.skip(endOfVersionString + 1)
      val idSize = source.readInt()
      val startPosition = endOfVersionString + 1 + 4

      return HprofParser(channel, source, startPosition, idSize)
    }
  }

}