package shark

import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.internal.ObjectIdEncoding
import shark.internal.UnsortedByteEntries

class SortedBytesMapTest {

  @Test fun writeAndReadLongValue() {
    val unsortedEntries = newEntries(bytesPerValue = 8, ids = listOf(1))
    unsortedEntries.append(1)
      .apply {
        writeLong(Long.MIN_VALUE)
      }

    val array = unsortedEntries.moveToSortedMap()[1]!!
    assertThat(array.readLong()).isEqualTo(Long.MIN_VALUE)
  }

  @Test fun writeAndReadTruncatedLongValue() {
    val maxUnsigned3Bytes = 0x00000FFFL
    val unsortedMap = newEntries(bytesPerValue = 3, ids = listOf(1))
    unsortedMap.append(1)
      .apply {
        writeTruncatedLong(maxUnsigned3Bytes, 3)
      }

    val array = unsortedMap.moveToSortedMap()[1]!!
    assertThat(array.readTruncatedLong(3)).isEqualTo(maxUnsigned3Bytes)
  }

  /**
   * Key bytes are sized from how far apart the ids are, so the same map has to round trip whether
   * that takes a single byte or all eight.
   */
  @Test fun everyKeyWidthRoundTrips() {
    for (keyByteCount in 1..8) {
      val span = if (keyByteCount == 8) Long.MAX_VALUE else (1L shl (keyByteCount * 8)) - 1
      val smallestId = -3L
      val ids = listOf(smallestId, smallestId + span / 3, smallestId + span / 2, smallestId + span)
      val desc = "$keyByteCount byte keys"

      val unsortedEntries = newEntries(bytesPerValue = 1, ids = ids)
      assertThat(encodingFor(ids).byteCount).`as`(desc).isEqualTo(keyByteCount)
      ids.forEachIndexed { index, id ->
        unsortedEntries.append(id).writeByte(index.toByte())
      }
      val map = unsortedEntries.moveToSortedMap()

      assertThat(map.entrySequence().map { it.first }.toList()).`as`(desc).isEqualTo(ids.sorted())
      ids.forEachIndexed { index, id ->
        assertThat(map.keyAt(map.indexOf(id))).`as`("$desc keyAt $id").isEqualTo(id)
        assertThat(map[id]!!.readByte()).`as`("$desc value of $id").isEqualTo(index.toByte())
      }
      // Ids on either side of the span are reported absent rather than clamped into it.
      assertThat(smallestId - 1 in map).`as`("$desc below span").isFalse
      assertThat(smallestId + span - 1 in map).`as`("$desc within span").isFalse
    }
  }

  @Test fun fourEntriesWithLongKey1ByteValueSorted() {
    val unsortedEntries =
      newEntries(bytesPerValue = 1, ids = listOf(42, 0, 3, Long.MAX_VALUE))
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
    val unsortedMap = newEntries(bytesPerValue = 3, ids = listOf(42, 0, 3, Long.MAX_VALUE))
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
      return "Entry(key=$key, value=${value.contentToString()})"
    }
  }

  @Test fun largeRandomArrayIntKey3ByteValueSorted() {
    val random = Random(Long.MAX_VALUE)

    val bytesPerValue = 3

    // The ids of an Android heap dump: 4 byte, so a span of at most 4 bytes.
    val sourceEntryArray = Array(10000) {
      Entry(random.nextInt().toLong(), random.nextBytes(bytesPerValue))
    }

    sortAndCompare(bytesPerValue, sourceEntryArray)
  }

  @Test fun largeRandomArrayLongKey3ByteValueSorted() {
    val random = Random(42)

    val bytesPerValue = 3

    // The ids of a JVM heap dump: 8 byte, but all within one heap's worth of address space.
    val heapStart = 0x7f9c40000000L
    val sourceEntryArray = Array(10000) {
      Entry(heapStart + random.nextInt().toLong(), random.nextBytes(bytesPerValue))
    }

    sortAndCompare(bytesPerValue, sourceEntryArray)
  }

  @Test fun largeRandomArrayLongKey7ByteValueSorted() {
    val random = Random(Long.MIN_VALUE)

    val bytesPerValue = 7

    // Ids spread over the whole 8 byte range, which no heap dump does but the encoding has to
    // handle: it falls back to storing them whole.
    val sourceEntryArray = Array(10000) {
      Entry(random.nextLong(), random.nextBytes(bytesPerValue))
    }

    sortAndCompare(bytesPerValue, sourceEntryArray)
  }

  private fun sortAndCompare(
    bytesPerValue: Int,
    sourceEntryArray: Array<Entry>
  ) {
    val unsortedEntries =
      newEntries(bytesPerValue = bytesPerValue, ids = sourceEntryArray.map { it.key })

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

  /**
   * Entries keyed the way the indexes of a heap dump holding exactly [ids] would be. Only the keys
   * are encoded, so `longIdentifiers` is left on: it sizes the ids written as *values*, which these
   * tests write as longs or bytes instead.
   */
  private fun newEntries(
    bytesPerValue: Int,
    ids: List<Long>
  ) = UnsortedByteEntries(
    idEncoding = encodingFor(ids),
    bytesPerValue = bytesPerValue,
    longIdentifiers = true
  )

  private fun encodingFor(ids: List<Long>): ObjectIdEncoding {
    var minId = Long.MAX_VALUE
    var maxId = Long.MIN_VALUE
    ids.forEach { id ->
      minId = minOf(minId, id)
      maxId = maxOf(maxId, id)
    }
    return ObjectIdEncoding.of(minId, maxId)
  }
}
