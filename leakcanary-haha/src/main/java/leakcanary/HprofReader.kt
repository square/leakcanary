package leakcanary

import leakcanary.Record.HeapDumpRecord.HeapDumpInfoRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
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
import leakcanary.ValueHolder.BooleanHolder
import leakcanary.ValueHolder.ByteHolder
import leakcanary.ValueHolder.CharHolder
import leakcanary.ValueHolder.DoubleHolder
import leakcanary.ValueHolder.FloatHolder
import leakcanary.ValueHolder.IntHolder
import leakcanary.ValueHolder.LongHolder
import leakcanary.ValueHolder.ReferenceHolder
import leakcanary.ValueHolder.ShortHolder
import okio.BufferedSource
import java.io.Closeable
import java.nio.charset.Charset

/**
 * Reads hprof content from an Okio [BufferedSource].
 *
 * Not thread safe, should be used from a single thread.
 */
open class HprofReader constructor(
  protected var source: BufferedSource,
  protected val startPosition: Long,
  val objectIdByteSize: Int
) : Closeable {
  override fun close() {
    source.close()
  }

  var position: Long = startPosition
    protected set

  val isOpen
    get() = source.isOpen

  val typeSizes = mapOf(
      // object
      PrimitiveType.REFERENCE_HPROF_TYPE to objectIdByteSize,
      BOOLEAN_TYPE to BOOLEAN_SIZE,
      CHAR_TYPE to CHAR_SIZE,
      FLOAT_TYPE to FLOAT_SIZE,
      DOUBLE_TYPE to DOUBLE_SIZE,
      BYTE_TYPE to BYTE_SIZE,
      SHORT_TYPE to SHORT_SIZE,
      INT_TYPE to INT_SIZE,
      LONG_TYPE to LONG_SIZE
  )

  fun readValue(type: Int): ValueHolder {
    return when (type) {
      PrimitiveType.REFERENCE_HPROF_TYPE -> ReferenceHolder(readId())
      BOOLEAN_TYPE -> BooleanHolder(readBoolean())
      CHAR_TYPE -> CharHolder(readChar())
      FLOAT_TYPE -> FloatHolder(readFloat())
      DOUBLE_TYPE -> DoubleHolder(readDouble())
      BYTE_TYPE -> ByteHolder(readByte())
      SHORT_TYPE -> ShortHolder(readShort())
      INT_TYPE -> IntHolder(readInt())
      LONG_TYPE -> LongHolder(readLong())
      else -> throw IllegalStateException("Unknown type $type")
    }
  }

  fun typeSize(type: Int): Int {
    return typeSizes.getValue(type)
  }

  fun readShort(): Short {
    position += SHORT_SIZE
    return source.readShort()
  }

  fun readInt(): Int {
    position += INT_SIZE
    return source.readInt()
  }

  fun readIdArray(arrayLength: Int): LongArray {
    return LongArray(arrayLength) { readId() }
  }

  fun readBooleanArray(arrayLength: Int): BooleanArray {
    return BooleanArray(arrayLength) { readByte().toInt() != 0 }
  }

  fun readCharArray(arrayLength: Int): CharArray {
    return readString(CHAR_SIZE * arrayLength, Charsets.UTF_16BE).toCharArray()
  }

  fun readString(
    byteCount: Int,
    charset: Charset
  ): String {
    position += byteCount
    return source.readString(byteCount.toLong(), charset)
  }

  fun readFloatArray(arrayLength: Int): FloatArray {
    return FloatArray(arrayLength) { readFloat() }
  }

  fun readDoubleArray(arrayLength: Int): DoubleArray {
    return DoubleArray(arrayLength) { readDouble() }
  }

  fun readShortArray(arrayLength: Int): ShortArray {
    return ShortArray(arrayLength) { readShort() }
  }

  fun readIntArray(arrayLength: Int): IntArray {
    return IntArray(arrayLength) { readInt() }
  }

  fun readLongArray(arrayLength: Int): LongArray {
    return LongArray(arrayLength) { readLong() }
  }

  fun readLong(): Long {
    position += LONG_SIZE
    return source.readLong()
  }

  fun exhausted() = source.exhausted()

  open fun skip(byteCount: Long) {
    position += byteCount
    return source.skip(byteCount)
  }

  fun readByte(): Byte {
    position += BYTE_SIZE
    return source.readByte()
  }

  fun readBoolean(): Boolean {
    position += BOOLEAN_SIZE
    return source.readByte().toInt() != 0
  }

  fun readByteArray(byteCount: Int): ByteArray {
    position += byteCount
    return source.readByteArray(byteCount.toLong())
  }

  fun readChar(): Char {
    return readString(CHAR_SIZE, Charsets.UTF_16BE)[0]
  }

  fun readFloat(): Float {
    return Float.fromBits(readInt())
  }

  fun readDouble(): Double {
    return Double.fromBits(readLong())
  }

  fun readId(): Long {
    // As long as we don't interpret IDs, reading signed values here is fine.
    return when (objectIdByteSize) {
      1 -> readByte().toLong()
      2 -> readShort().toLong()
      4 -> readInt().toLong()
      8 -> readLong()
      else -> throw IllegalArgumentException("ID Length must be 1, 2, 4, or 8")
    }
  }

  fun readUtf8(byteCount: Long): String {
    position += byteCount
    return source.readUtf8(byteCount)
  }

  fun readUnsignedInt(): Long {
    return readInt().toLong() and INT_MASK
  }

  fun readUnsignedByte(): Int {
    return readByte().toInt() and BYTE_MASK
  }

  fun readUnsignedShort(): Int {
    return readShort().toInt() and 0xFFFF
  }

  fun skip(byteCount: Int) {
    position += byteCount
    return source.skip(byteCount.toLong())
  }

  fun readInstanceDumpRecord(): InstanceDumpRecord {
    val id = readId()
    val stackTraceSerialNumber = readInt()
    val classId = readId()
    val remainingBytesInInstance = readInt()
    val fieldValues = readByteArray(remainingBytesInInstance)
    return InstanceDumpRecord(
        id = id,
        stackTraceSerialNumber = stackTraceSerialNumber,
        classId = classId,
        fieldValues = fieldValues
    )
  }

  fun readClassDumpRecord(): ClassDumpRecord {
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
      skip(SHORT_SIZE)
      skip(typeSize(readUnsignedByte()))
    }

    val staticFieldCount = readUnsignedShort()
    val staticFields = ArrayList<StaticFieldRecord>(staticFieldCount)
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

    val fieldCount = readUnsignedShort()
    val fields = ArrayList<FieldRecord>(fieldCount)
    for (i in 0 until fieldCount) {
      fields.add(FieldRecord(nameStringId = readId(), type = readUnsignedByte()))
    }

    return ClassDumpRecord(
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
  }

  fun skipInstanceDumpRecord() {
    skip(objectIdByteSize + INT_SIZE + objectIdByteSize)
    val remainingBytesInInstance = readInt()
    skip(remainingBytesInInstance)
  }

  fun skipClassDumpRecord() {
    skip(
        objectIdByteSize + INT_SIZE + objectIdByteSize + objectIdByteSize + objectIdByteSize + objectIdByteSize + objectIdByteSize + objectIdByteSize + INT_SIZE
    )
    // Skip over the constant pool
    val constantPoolCount = readUnsignedShort()
    for (i in 0 until constantPoolCount) {
      // constant pool index
      skip(SHORT_SIZE)
      skip(typeSize(readUnsignedByte()))
    }

    val staticFieldCount = readUnsignedShort()

    for (i in 0 until staticFieldCount) {
      skip(objectIdByteSize)
      val type = readUnsignedByte()
      skip(typeSize(type))
    }

    val fieldCount = readUnsignedShort()
    skip(fieldCount * (objectIdByteSize + BYTE_SIZE))
  }

  fun readObjectArrayDumpRecord(
  ): ObjectArrayDumpRecord {
    val id = readId()
    // stack trace serial number
    val stackTraceSerialNumber = readInt()
    val arrayLength = readInt()
    val arrayClassId = readId()
    val elementIds = readIdArray(arrayLength)
    return ObjectArrayDumpRecord(
        id = id,
        stackTraceSerialNumber = stackTraceSerialNumber,
        arrayClassId = arrayClassId,
        elementIds = elementIds
    )
  }

  fun skipObjectArrayDumpRecord() {
    skip(objectIdByteSize + INT_SIZE)
    val arrayLength = readInt()
    skip(objectIdByteSize + arrayLength * objectIdByteSize)
  }

  fun readPrimitiveArrayDumpRecord(): PrimitiveArrayDumpRecord {
    val id = readId()
    val stackTraceSerialNumber = readInt()
    // length
    val arrayLength = readInt()
    val type = readUnsignedByte()
    return when (type) {
      BOOLEAN_TYPE -> BooleanArrayDump(
          id, stackTraceSerialNumber, readBooleanArray(arrayLength)
      )
      CHAR_TYPE -> CharArrayDump(
          id, stackTraceSerialNumber, readCharArray(arrayLength)
      )
      FLOAT_TYPE -> FloatArrayDump(
          id, stackTraceSerialNumber, readFloatArray(arrayLength)
      )
      DOUBLE_TYPE -> DoubleArrayDump(
          id, stackTraceSerialNumber, readDoubleArray(arrayLength)
      )
      BYTE_TYPE -> ByteArrayDump(
          id, stackTraceSerialNumber, readByteArray(arrayLength)
      )
      SHORT_TYPE -> ShortArrayDump(
          id, stackTraceSerialNumber, readShortArray(arrayLength)
      )
      INT_TYPE -> IntArrayDump(
          id, stackTraceSerialNumber, readIntArray(arrayLength)
      )
      LONG_TYPE -> LongArrayDump(
          id, stackTraceSerialNumber, readLongArray(arrayLength)
      )
      else -> throw IllegalStateException("Unexpected type $type")
    }
  }

  fun skipPrimitiveArrayDumpRecord() {
    skip(objectIdByteSize + INT_SIZE)
    val arrayLength = readInt()
    val type = readUnsignedByte()
    skip(objectIdByteSize + arrayLength * typeSize(type))
  }

  fun readHeapDumpInfoRecord(): HeapDumpInfoRecord {
    val heapId = readInt()
    return HeapDumpInfoRecord(heapId = heapId, heapNameStringId = readId())
  }

  fun skipHeapDumpInfoRecord() {
    skip(objectIdByteSize + objectIdByteSize)
  }

  companion object {
    private val BOOLEAN_SIZE = PrimitiveType.BOOLEAN.byteSize
    private val CHAR_SIZE = PrimitiveType.CHAR.byteSize
    private val FLOAT_SIZE = PrimitiveType.FLOAT.byteSize
    private val DOUBLE_SIZE = PrimitiveType.DOUBLE.byteSize
    private val BYTE_SIZE = PrimitiveType.BYTE.byteSize
    private val SHORT_SIZE = PrimitiveType.SHORT.byteSize
    private val INT_SIZE = PrimitiveType.INT.byteSize
    private val LONG_SIZE = PrimitiveType.LONG.byteSize

    private val BOOLEAN_TYPE = PrimitiveType.BOOLEAN.hprofType
    private val CHAR_TYPE = PrimitiveType.CHAR.hprofType
    private val FLOAT_TYPE = PrimitiveType.FLOAT.hprofType
    private val DOUBLE_TYPE = PrimitiveType.DOUBLE.hprofType
    private val BYTE_TYPE = PrimitiveType.BYTE.hprofType
    private val SHORT_TYPE = PrimitiveType.SHORT.hprofType
    private val INT_TYPE = PrimitiveType.INT.hprofType
    private val LONG_TYPE = PrimitiveType.LONG.hprofType

    private const val INT_MASK = 0xffffffffL
    private const val BYTE_MASK = 0xff
  }

}
