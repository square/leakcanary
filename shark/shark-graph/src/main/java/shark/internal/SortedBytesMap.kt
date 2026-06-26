package shark.internal

import shark.internal.hppc.LongObjectPair
import shark.internal.hppc.to

/**
 * A read only map of `id` => `byte array` sorted by id, where `id` is a long if `longIdentifiers`
 * is true and an int otherwise. Each entry has a value byte array of a fixed size.
 *
 * Instances are created by [SortedBytesMapBuilder.moveToSortedMap]. Two implementations exist:
 * [ArraySortedBytesMap], backed by a single [ByteArray], and [PagedSortedBytesMap], backed by
 * several pages so that the total size can exceed the JVM's ~2 GB single-array limit.
 *
 * The [Int] index exposed via [indexOf], [getAtIndex] and [keyAt] is a position into the global,
 * id-sorted sequence of entries: `getAtIndex(indexOf(key))` round trips and `keyAt` / [getAtIndex]
 * walk entries in ascending id order.
 */
internal interface SortedBytesMap {

  val size: Int

  operator fun get(key: Long): ByteSubArray?

  operator fun contains(key: Long): Boolean

  /**
   * Returns a non negative index if [key] is present, otherwise a negative number.
   */
  fun indexOf(key: Long): Int

  fun getAtIndex(keyIndex: Int): ByteSubArray

  fun keyAt(index: Int): Long

  fun entrySequence(): Sequence<LongObjectPair<ByteSubArray>>
}

/**
 * A [SortedBytesMap] backed by a single sorted [ByteArray]. [get] and [contains] perform a binary
 * search to locate a specific entry by key.
 */
internal class ArraySortedBytesMap(
  private val longIdentifiers: Boolean,
  private val bytesPerValue: Int,
  private val sortedEntries: ByteArray
) : SortedBytesMap {
  private val bytesPerKey = if (longIdentifiers) 8 else 4
  private val bytesPerEntry = bytesPerKey + bytesPerValue

  override val size = sortedEntries.size / bytesPerEntry

  override operator fun get(key: Long): ByteSubArray? {
    val keyIndex = binarySearch(key)
    if (keyIndex < 0) {
      return null
    }
    return getAtIndex(keyIndex)
  }

  override fun indexOf(key: Long): Int {
    return binarySearch(key)
  }

  override fun getAtIndex(keyIndex: Int): ByteSubArray {
    val valueIndex = keyIndex * bytesPerEntry + bytesPerKey
    return ByteSubArray(sortedEntries, valueIndex, bytesPerValue, longIdentifiers)
  }

  override operator fun contains(key: Long): Boolean {
    val keyIndex = binarySearch(key)
    return keyIndex >= 0
  }

  override fun entrySequence(): Sequence<LongObjectPair<ByteSubArray>> {
    return (0 until size).asSequence()
      .map { keyIndex ->
        val valueIndex = keyIndex * bytesPerEntry + bytesPerKey
        keyAt(keyIndex) to ByteSubArray(sortedEntries, valueIndex, bytesPerValue, longIdentifiers)
      }
  }

  private fun binarySearch(
    key: Long
  ): Int {
    val startIndex = 0
    val endIndex = size
    var lo = startIndex
    var hi = endIndex - 1
    while (lo <= hi) {
      val mid = (lo + hi).ushr(1)
      val midVal = keyAt(mid)
      when {
        midVal < key -> lo = mid + 1
        midVal > key -> hi = mid - 1
        else -> return mid
      }
    }
    return lo.inv()
  }

  override fun keyAt(index: Int): Long {
    val keyIndex = index * bytesPerEntry
    return if (longIdentifiers) {
      sortedEntries.readLong(keyIndex)
    } else {
      sortedEntries.readInt(keyIndex).toLong()
    }
  }
}
