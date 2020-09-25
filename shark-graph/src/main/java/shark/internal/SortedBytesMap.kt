package shark.internal

import shark.internal.hppc.LongObjectPair
import shark.internal.hppc.to

/**
 * A read only map of `id` => `byte array` sorted by id, where `id` is a long if [longIdentifiers]
 * is true and an int otherwise. Each entry has a value byte array of size [bytesPerValue].
 *
 * Instances are created by [UnsortedByteEntries]
 *
 * [get] and [contains] perform a binary search to locate a specific entry by key.
 */
internal class SortedBytesMap(
  private val longIdentifiers: Boolean,
  private val bytesPerValue: Int,
  private val sortedEntries: ByteArray
) {
  private val bytesPerKey = if (longIdentifiers) 8 else 4
  private val bytesPerEntry = bytesPerKey + bytesPerValue

  val size = sortedEntries.size / bytesPerEntry

  operator fun get(key: Long): ByteSubArray? {
    val keyIndex = binarySearch(key)
    if (keyIndex < 0) {
      return null
    }
    return getAtIndex(keyIndex)
  }

  fun indexOf(key: Long): Int {
    return binarySearch(key)
  }

  fun getAtIndex(keyIndex: Int): ByteSubArray {
    val valueIndex = keyIndex * bytesPerEntry + bytesPerKey
    return ByteSubArray(sortedEntries, valueIndex, bytesPerValue, longIdentifiers)
  }

  operator fun contains(key: Long): Boolean {
    val keyIndex = binarySearch(key)
    return keyIndex >= 0
  }

  fun entrySequence(): Sequence<LongObjectPair<ByteSubArray>> {
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

  fun keyAt(index: Int): Long {
    val keyIndex = index * bytesPerEntry
    return if (longIdentifiers) {
      sortedEntries.readLong(keyIndex)
    } else {
      sortedEntries.readInt(keyIndex).toLong()
    }
  }
}