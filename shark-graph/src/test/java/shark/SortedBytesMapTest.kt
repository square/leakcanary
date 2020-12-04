package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.internal.UnsortedByteEntries
import java.util.Arrays
import kotlin.random.Random

class SortedBytesMapTest {

  @Test fun writeAndReadLongValue() {
    val unsortedEntries = UnsortedByteEntries(bytesPerValue = 8, longIdentifiers = false)
    unsortedEntries.append(1)
      .apply {
        writeLong(Long.MIN_VALUE)
      }

    val array = unsortedEntries.moveToSortedMap()[1]!!
    assertThat(array.readLong()).isEqualTo(Long.MIN_VALUE)
  }

  @Test fun writeAndReadTruncatedLongValue() {
    val maxUnsigned3Bytes = 0x00000FFFL
    val unsortedMap = UnsortedByteEntries(bytesPerValue = 3, longIdentifiers = false)
    unsortedMap.append(1)
      .apply {
        writeTruncatedLong(maxUnsigned3Bytes, 3)
      }

    val array = unsortedMap.moveToSortedMap()[1]!!
    assertThat(array.readTruncatedLong(3)).isEqualTo(maxUnsigned3Bytes)
  }

  @Test fun fourEntriesWithLongKey1ByteValueSorted() {
    val unsortedEntries = UnsortedByteEntries(bytesPerValue = 1, longIdentifiers = true)
    unsortedEntries.append(42)
      .apply {
        writeByte(4)
      }
    unsortedEntries.append(0)
      .apply {
        writeByte(3)
      }
    unsortedEntries.append(3)
      .apply {
        writeByte(20)
      }
    unsortedEntries.append(Long.MAX_VALUE)
      .apply {
        writeByte(127)
      }
    val sortedEntries = unsortedEntries.moveToSortedMap()
      .entrySequence()
      .toList()

    assertThat(sortedEntries.map { it.first }).containsExactly(0, 3, 42, Long.MAX_VALUE)
    assertThat(
      sortedEntries.map {
        byteArrayOf(
          it.second.readByte()
        )
      }).containsExactly(
      byteArrayOf(3), byteArrayOf(20), byteArrayOf(4),
      byteArrayOf(127)
    )
  }

  @Test fun fourEntriesWithLongKey3ByteValueSorted() {
    val unsortedMap = UnsortedByteEntries(bytesPerValue = 3, longIdentifiers = true)
    unsortedMap.append(42)
      .apply {
        writeByte(4)
        writeByte(2)
        writeByte(0)
      }
    unsortedMap.append(0)
      .apply {
        writeByte(3)
        writeByte(2)
        writeByte(1)
      }
    unsortedMap.append(3)
      .apply {
        writeByte(20)
        writeByte(52)
        writeByte(-17)
      }
    unsortedMap.append(Long.MAX_VALUE)
      .apply {
        writeByte(127)
        writeByte(0)
        writeByte(-128)
      }
    val sortedEntries = unsortedMap.moveToSortedMap()
      .entrySequence()
      .toList()

    assertThat(sortedEntries.map { it.first }).containsExactly(0, 3, 42, Long.MAX_VALUE)
    assertThat(
      sortedEntries.map {
        byteArrayOf(
          it.second.readByte(), it.second.readByte(), it.second.readByte()
        )
      }).containsExactly(
      byteArrayOf(3, 2, 1), byteArrayOf(20, 52, -17), byteArrayOf(4, 2, 0),
      byteArrayOf(127, 0, -128)
    )
  }

  class Entry(
    val key: Long,
    val value: ByteArray
  ) : Comparable<Entry> {
    override fun compareTo(other: Entry): Int = key.compareTo(other.key)
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Entry

      if (key != other.key) return false
      if (!value.contentEquals(other.value)) return false

      return true
    }

    override fun toString(): String {
      return "Entry(key=$key, value=${Arrays.toString(value)})"
    }
  }

  @Test fun largeRandomArrayIntKey3ByteValueSorted() {
    val random = Random(Long.MAX_VALUE)

    val bytesPerValue = 3
    val longIdentifiers = false

    val sourceEntryArray = Array(10000) {
      Entry(random.nextInt().toLong(), random.nextBytes(bytesPerValue))
    }

    sortAndCompare(bytesPerValue, longIdentifiers, sourceEntryArray)
  }

  @Test fun largeRandomArrayLongKey3ByteValueSorted() {
    val random = Random(42)

    val bytesPerValue = 3
    val longIdentifiers = true

    val sourceEntryArray = Array(10000) {
      Entry(random.nextLong(), random.nextBytes(bytesPerValue))
    }

    sortAndCompare(bytesPerValue, longIdentifiers, sourceEntryArray)
  }

  @Test fun largeRandomArrayLongKey7ByteValueSorted() {
    val random = Random(Long.MIN_VALUE)

    val bytesPerValue = 7
    val longIdentifiers = true

    val sourceEntryArray = Array(10000) {
      Entry(random.nextLong(), random.nextBytes(bytesPerValue))
    }

    sortAndCompare(bytesPerValue, longIdentifiers, sourceEntryArray)
  }

  private fun sortAndCompare(
    bytesPerValue: Int,
    longIdentifiers: Boolean,
    sourceEntryArray: Array<Entry>
  ) {
    val unsortedEntries =
      UnsortedByteEntries(bytesPerValue = bytesPerValue, longIdentifiers = longIdentifiers)

    sourceEntryArray.forEach { entry ->
      val subArray = unsortedEntries.append(entry.key)
      entry.value.forEach { subArray.writeByte(it) }
    }

    val sortedMap = unsortedEntries.moveToSortedMap()
    sourceEntryArray.sort()

    val sortedEntryArray = sortedMap.entrySequence()
      .map {
        val key = it.first
        val value = it.second

        val bytes = mutableListOf<Byte>()
        for (i in 0 until bytesPerValue) {
          bytes += value.readByte()
        }
        Entry(key, bytes.toByteArray())
      }
      .toList()
      .toTypedArray()

    assertThat(sortedEntryArray).isEqualTo(sourceEntryArray)
  }
}
