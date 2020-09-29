package shark.internal

import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import shark.PrimitiveType
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.CHAR
import shark.PrimitiveType.DOUBLE
import shark.PrimitiveType.FLOAT
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT
import shark.ValueHolder
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ByteHolder
import shark.ValueHolder.CharHolder
import shark.ValueHolder.DoubleHolder
import shark.ValueHolder.FloatHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.ValueHolder.ShortHolder
import shark.internal.IndexedObject.IndexedClass

internal class ClassFieldsReader(
  private val identifierByteSize: Int,
  private val classFieldBytes: ByteArray
) {

  private var position = 0

  fun classDumpStaticFields(indexedClass: IndexedClass): List<StaticFieldRecord> {
    position = indexedClass.fieldsIndex
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
    return staticFields
  }

  fun classDumpFields(indexedClass: IndexedClass): List<FieldRecord> {
    position = indexedClass.fieldsIndex

    skipStaticFields()

    val fieldCount = readUnsignedShort()
    val fields = ArrayList<FieldRecord>(fieldCount)
    for (i in 0 until fieldCount) {
      fields.add(FieldRecord(nameStringId = readId(), type = readUnsignedByte()))
    }
    return fields
  }

  fun classDumpHasReferenceFields(indexedClass: IndexedClass): Boolean {
    position = indexedClass.fieldsIndex
    skipStaticFields()
    val fieldCount = readUnsignedShort()
    for (i in 0 until fieldCount) {
      position += identifierByteSize
      val type = readUnsignedByte()
      if (type == PrimitiveType.REFERENCE_HPROF_TYPE) {
        return true
      }
    }
    return false
  }

  private fun skipStaticFields() {
    val staticFieldCount = readUnsignedShort()
    for (i in 0 until staticFieldCount) {
      position += identifierByteSize
      val type = readUnsignedByte()
      position += if (type == PrimitiveType.REFERENCE_HPROF_TYPE) {
        identifierByteSize
      } else {
        PrimitiveType.byteSizeByHprofType.getValue(type)
      }
    }
  }

  private fun readValue(type: Int): ValueHolder {
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

  private fun readByte(): Byte {
    return classFieldBytes[position++]
  }

  private fun readInt(): Int {
    return (classFieldBytes[position++].toInt() and 0xff shl 24) or
        (classFieldBytes[position++].toInt() and 0xff shl 16) or
        (classFieldBytes[position++].toInt() and 0xff shl 8) or
        (classFieldBytes[position++].toInt() and 0xff)
  }

  private fun readLong(): Long {
    return (classFieldBytes[position++].toLong() and 0xff shl 56) or
        (classFieldBytes[position++].toLong() and 0xff shl 48) or
        (classFieldBytes[position++].toLong() and 0xff shl 40) or
        (classFieldBytes[position++].toLong() and 0xff shl 32) or
        (classFieldBytes[position++].toLong() and 0xff shl 24) or
        (classFieldBytes[position++].toLong() and 0xff shl 16) or
        (classFieldBytes[position++].toLong() and 0xff shl 8) or
        (classFieldBytes[position++].toLong() and 0xff)
  }

  private fun readShort(): Short {
    return ((classFieldBytes[position++].toInt() and 0xff shl 8) or
        (classFieldBytes[position++].toInt() and 0xff)).toShort()
  }

  private fun readUnsignedShort(): Int {
    return readShort().toInt() and 0xFFFF
  }

  private fun readUnsignedByte(): Int {
    return readByte().toInt() and 0xFF
  }

  private fun readId(): Long {
    // As long as we don't interpret IDs, reading signed values here is fine.
    return when (identifierByteSize) {
      1 -> readByte().toLong()
      2 -> readShort().toLong()
      4 -> readInt().toLong()
      8 -> readLong()
      else -> throw IllegalArgumentException("ID Length must be 1, 2, 4, or 8")
    }
  }

  private fun readBoolean(): Boolean {
    return readByte()
        .toInt() != 0
  }

  private fun readChar(): Char {
    return readShort().toChar()
  }

  private fun readFloat(): Float {
    return Float.fromBits(readInt())
  }

  private fun readDouble(): Double {
    return Double.fromBits(readLong())
  }

  companion object {
    private val BOOLEAN_TYPE = BOOLEAN.hprofType
    private val CHAR_TYPE = CHAR.hprofType
    private val FLOAT_TYPE = FLOAT.hprofType
    private val DOUBLE_TYPE = DOUBLE.hprofType
    private val BYTE_TYPE = BYTE.hprofType
    private val SHORT_TYPE = SHORT.hprofType
    private val INT_TYPE = INT.hprofType
    private val LONG_TYPE = LONG.hprofType
  }
}