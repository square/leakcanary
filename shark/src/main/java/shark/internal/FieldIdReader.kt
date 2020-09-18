package shark.internal

import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord

/**
 * Simplified version of [FieldValuesReader] class that can only read an ID or skip a certain
 * amount of bytes.
 */
internal class FieldIdReader(
  private val record: InstanceDumpRecord,
  private val identifierByteSize: Int) {

  private var position = 0

  fun readId(): Long {
    // As long as we don't interpret IDs, reading signed values here is fine.
    val value = when (identifierByteSize) {
      1 -> readByteId(position, record.fieldValues)
      2 -> readShortId(position, record.fieldValues)
      4 -> readIntId(position, record.fieldValues)
      8 -> readLongId(position, record.fieldValues)
      else -> error("ID Length must be 1, 2, 4, or 8")
    }
    position += identifierByteSize
    return value
  }

  fun skipBytes(count: Int) {
    position += count
  }

  private fun readByteId(index: Int, array: ByteArray) =
    array[index].toLong()

  private fun readShortId(index: Int, array: ByteArray): Long {
    var pos = index
    return (array[pos++] and 0xff shl 8
        or (array[pos] and 0xff)).toLong()
  }

  private fun readIntId(index: Int, array: ByteArray): Long {
    var pos = index
    return (array[pos++] and 0xff shl 24
        or (array[pos++] and 0xff shl 16)
        or (array[pos++] and 0xff shl 8)
        or (array[pos] and 0xff)).toLong()
  }

  private fun readLongId(index: Int, array: ByteArray): Long {
    var pos = index
    return (array[pos++] and 0xffL shl 56
        or (array[pos++] and 0xffL shl 48)
        or (array[pos++] and 0xffL shl 40)
        or (array[pos++] and 0xffL shl 32)
        or (array[pos++] and 0xffL shl 24)
        or (array[pos++] and 0xffL shl 16)
        or (array[pos++] and 0xffL shl 8)
        or (array[pos] and 0xffL))
  }

  @Suppress("NOTHING_TO_INLINE") // Syntactic sugar.
  private inline infix fun Byte.and(other: Long): Long = toLong() and other

  @Suppress("NOTHING_TO_INLINE") // Syntactic sugar.
  private inline infix fun Byte.and(other: Int): Int = toInt() and other
}