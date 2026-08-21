package shark.internal

import shark.internal.aosp.ByteArrayTimSort

/**
 * Wraps a byte array of entries where each entry is an id key stored the way [idEncoding] describes,
 * followed by [bytesPerValue] bytes for the value. Entries are appended into the array via [append].
 * Once done, the backing array is sorted and turned into a [SortedBytesMap] by calling
 * [moveToSortedMap].
 */
internal class UnsortedByteEntries(
  private val idEncoding: ObjectIdEncoding,
  private val bytesPerValue: Int,
  private val longIdentifiers: Boolean,
  private val initialCapacity: Int = 4,
  private val growthFactor: Double = 2.0
) : SortedBytesMapBuilder {

  private val bytesPerKey = idEncoding.byteCount
  private val bytesPerEntry = bytesPerValue + bytesPerKey

  private var entries: ByteArray? = null
  private val subArray = MutableByteSubArray()
  private var subArrayIndex = 0

  private var assigned: Int = 0
  private var currentCapacity = 0

  override fun append(
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
    subArray.writeTruncatedLong(idEncoding.encode(key), bytesPerKey)
    return subArray
  }

  override fun moveToSortedMap(): SortedBytesMap {
    if (assigned == 0) {
      return ArraySortedBytesMap(idEncoding, longIdentifiers, bytesPerValue, ByteArray(0))
    }
    val entries = entries!!
    // Sorting on the encoded keys, which are offsets from the same base and so in the same order as
    // the ids they encode.
    val idEncoding = idEncoding
    ByteArrayTimSort.sort(entries, 0, assigned, bytesPerEntry) {
        entrySize, o1Array, o1Index, o2Array, o2Index ->
      idEncoding.encodedKeyAt(o1Array, o1Index * entrySize)
        .compareTo(
          idEncoding.encodedKeyAt(o2Array, o2Index * entrySize)
        )
    }
    val sortedEntries = if (entries.size > assigned * bytesPerEntry) {
      entries.copyOf(assigned * bytesPerEntry)
    } else entries
    this.entries = null
    assigned = 0
    return ArraySortedBytesMap(
      idEncoding, longIdentifiers, bytesPerValue, sortedEntries
    )
  }

  private fun growEntries(newCapacity: Int) {
    val newEntries = ByteArray(newCapacity * bytesPerEntry)
    System.arraycopy(entries, 0, newEntries, 0, assigned * bytesPerEntry)
    entries = newEntries
  }

  internal inner class MutableByteSubArray : ByteSubArrayWriter {
    override fun writeByte(value: Byte) {
      val index = subArrayIndex
      subArrayIndex++
      require(index in 0..bytesPerEntry) {
        "Index $index should be between 0 and $bytesPerEntry"
      }
      val valuesIndex = ((assigned - 1) * bytesPerEntry) + index
      entries!![valuesIndex] = value
    }

    override fun writeId(value: Long) {
      if (longIdentifiers) {
        writeLong(value)
      } else {
        writeInt(value.toInt())
      }
    }

    override fun writeInt(value: Int) {
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

    override fun writeTruncatedLong(
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

    override fun writeLong(value: Long) {
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

