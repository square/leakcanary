package shark.internal

import shark.internal.aosp.ByteArrayComparator
import shark.internal.aosp.ByteArrayTimSort

/**
 * Wraps a byte array of entries where each entry is an id followed by bytes for the value.
 * `id` is a long if [longIdentifiers] is true and an int otherwise. Each entry has [bytesPerValue]
 * value bytes. Entries are appended into the array via [append]. Once done, the backing array
 * is sorted and turned into a [SortedBytesMap] by calling [moveToSortedMap].
 */
internal class UnsortedByteEntries(
  private val bytesPerValue: Int,
  private val longIdentifiers: Boolean,
  private val initialCapacity: Int = 4,
  private val growthFactor: Double = 2.0
) {

  private val bytesPerEntry = bytesPerValue + if (longIdentifiers) 8 else 4

  private var entries: ByteArray? = null
  private val subArray = MutableByteSubArray()
  private var subArrayIndex = 0

  private var assigned: Int = 0
  private var currentCapacity = 0

  fun append(
    key: Long
  ): MutableByteSubArray {
    if (entries == null) {
      currentCapacity = initialCapacity
      entries = ByteArray(currentCapacity * bytesPerEntry)
    } else {
      if (currentCapacity == assigned) {
        val newCapacity = (currentCapacity * growthFactor).toInt()
        growEntries(newCapacity)
        currentCapacity = newCapacity
      }
    }
    assigned++
    subArrayIndex = 0
    subArray.writeId(key)
    return subArray
  }

  fun moveToSortedMap(): SortedBytesMap {
    if (assigned == 0) {
      return SortedBytesMap(longIdentifiers, bytesPerValue, ByteArray(0))
    }
    val entries = entries!!
    // Sort entries by keys, which are ids of 4 or 8 bytes.
    ByteArrayTimSort.sort(entries, 0, assigned, bytesPerEntry, object : ByteArrayComparator {
      override fun compare(
        entrySize: Int,
        o1Array: ByteArray,
        o1Index: Int,
        o2Array: ByteArray,
        o2Index: Int
      ): Int {
        return if (longIdentifiers) {
          readLong(o1Array, o1Index * entrySize)
            .compareTo(
              readLong(o2Array, o2Index * entrySize)
            )
        } else {
          readInt(o1Array, o1Index * entrySize)
            .compareTo(
              readInt(o2Array, o2Index * entrySize)
            )
        }
      }
    })
    val sortedEntries = if (entries.size > assigned * bytesPerEntry) {
      entries.copyOf(assigned * bytesPerEntry)
    } else entries
    this.entries = null
    assigned = 0
    return SortedBytesMap(
      longIdentifiers, bytesPerValue, sortedEntries
    )
  }

  private fun readInt(
    array: ByteArray,
    index: Int
  ): Int {
    var pos = index
    return (array[pos++] and 0xff shl 24
      or (array[pos++] and 0xff shl 16)
      or (array[pos++] and 0xff shl 8)
      or (array[pos] and 0xff))
  }

  @Suppress("NOTHING_TO_INLINE") // Syntactic sugar.
  private inline infix fun Byte.and(other: Long): Long = toLong() and other

  @Suppress("NOTHING_TO_INLINE") // Syntactic sugar.
  private inline infix fun Byte.and(other: Int): Int = toInt() and other

  private fun readLong(
    array: ByteArray,
    index: Int
  ): Long {
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

  private fun growEntries(newCapacity: Int) {
    val newEntries = ByteArray(newCapacity * bytesPerEntry)
    System.arraycopy(entries, 0, newEntries, 0, assigned * bytesPerEntry)
    entries = newEntries
  }

  internal inner class MutableByteSubArray {
    fun writeByte(value: Byte) {
      val index = subArrayIndex
      subArrayIndex++
      require(index in 0..bytesPerEntry) {
        "Index $index should be between 0 and $bytesPerEntry"
      }
      val valuesIndex = ((assigned - 1) * bytesPerEntry) + index
      entries!![valuesIndex] = value
    }

    fun writeId(value: Long) {
      if (longIdentifiers) {
        writeLong(value)
      } else {
        writeInt(value.toInt())
      }
    }

    fun writeInt(value: Int) {
      val index = subArrayIndex
      subArrayIndex += 4
      require(index >= 0 && index <= bytesPerEntry - 4) {
        "Index $index should be between 0 and ${bytesPerEntry - 4}"
      }
      var pos = ((assigned - 1) * bytesPerEntry) + index
      val values = entries!!
      values[pos++] = (value ushr 24 and 0xff).toByte()
      values[pos++] = (value ushr 16 and 0xff).toByte()
      values[pos++] = (value ushr 8 and 0xff).toByte()
      values[pos] = (value and 0xff).toByte()
    }

    fun writeTruncatedLong(
      value: Long,
      byteCount: Int
    ) {
      val index = subArrayIndex
      subArrayIndex += byteCount
      require(index >= 0 && index <= bytesPerEntry - byteCount) {
        "Index $index should be between 0 and ${bytesPerEntry - byteCount}"
      }
      var pos = ((assigned - 1) * bytesPerEntry) + index
      val values = entries!!

      var shift = (byteCount - 1) * 8
      while (shift >= 8) {
        values[pos++] = (value ushr shift and 0xffL).toByte()
        shift -= 8
      }
      values[pos] = (value and 0xffL).toByte()
    }

    fun writeLong(value: Long) {
      val index = subArrayIndex
      subArrayIndex += 8
      require(index >= 0 && index <= bytesPerEntry - 8) {
        "Index $index should be between 0 and ${bytesPerEntry - 8}"
      }
      var pos = ((assigned - 1) * bytesPerEntry) + index
      val values = entries!!
      values[pos++] = (value ushr 56 and 0xffL).toByte()
      values[pos++] = (value ushr 48 and 0xffL).toByte()
      values[pos++] = (value ushr 40 and 0xffL).toByte()
      values[pos++] = (value ushr 32 and 0xffL).toByte()
      values[pos++] = (value ushr 24 and 0xffL).toByte()
      values[pos++] = (value ushr 16 and 0xffL).toByte()
      values[pos++] = (value ushr 8 and 0xffL).toByte()
      values[pos] = (value and 0xffL).toByte()
    }
  }
}

