package shark.internal

import shark.internal.hppc.LongObjectPair
import shark.internal.hppc.to

/**
 * A [SortedBytesMap] backed by a globally id-sorted [PagedByteArray] (see
 * [PagedUnsortedByteEntries]). Because the pages are sorted and contiguous, each page covers a
 * disjoint, ascending range of ids. A lookup binary searches [pageFirstKeys] (one key per page) to
 * pick the page that may hold the key, then binary searches within that single page.
 */
internal class PagedSortedBytesMap(
  private val entries: PagedByteArray
) : SortedBytesMap {

  private val idEncoding = entries.idEncoding
  private val longIdentifiers = entries.longIdentifiers
  private val bytesPerKey = entries.bytesPerKey
  private val bytesPerValue = entries.bytesPerEntry - bytesPerKey
  private val pageCount = entries.pages.size

  /**
   * Smallest key in each page (its first entry, since the whole array is key sorted), encoded like
   * the entries themselves so that a lookup compares encoded keys throughout.
   */
  private val pageFirstKeys = LongArray(pageCount) { page ->
    entries.readEncodedKey(page shl entries.pageShift)
  }

  override val size = entries.entryCount

  override operator fun get(key: Long): ByteSubArray? {
    val keyIndex = indexOf(key)
    return if (keyIndex < 0) null else getAtIndex(keyIndex)
  }

  override operator fun contains(key: Long): Boolean = indexOf(key) >= 0

  override fun indexOf(key: Long): Int {
    if (pageCount == 0) return -1
    val encodedKey = idEncoding.encode(key)
    val page = pageFor(encodedKey)
    val pageStart = page shl entries.pageShift
    val local = binarySearchInPage(pageStart, entries.entriesInPage(page), encodedKey)
    return if (local < 0) local else pageStart + local
  }

  override fun getAtIndex(keyIndex: Int): ByteSubArray {
    val page = entries.pages[keyIndex shr entries.pageShift]
    val valueIndex = (keyIndex and entries.pageMask) * entries.bytesPerEntry + bytesPerKey
    return ByteSubArray(page, valueIndex, bytesPerValue, longIdentifiers)
  }

  override fun keyAt(index: Int): Long = idEncoding.decode(entries.readEncodedKey(index))

  override fun entrySequence(): Sequence<LongObjectPair<ByteSubArray>> {
    return (0 until size).asSequence()
      .map { keyIndex -> keyAt(keyIndex) to getAtIndex(keyIndex) }
  }

  /**
   * Index of the rightmost page whose first key is <= [key] (0 if [key] precedes every page). Both
   * [key] and [pageFirstKeys] are encoded keys.
   */
  private fun pageFor(key: Long): Int {
    var lo = 0
    var hi = pageCount - 1
    while (lo < hi) {
      val mid = (lo + hi + 1).ushr(1)
      if (pageFirstKeys[mid] <= key) {
        lo = mid
      } else {
        hi = mid - 1
      }
    }
    return lo
  }

  /** Searches a single page for the encoded [key]. */
  private fun binarySearchInPage(
    pageStart: Int,
    count: Int,
    key: Long
  ): Int {
    var lo = 0
    var hi = count - 1
    while (lo <= hi) {
      val mid = (lo + hi).ushr(1)
      val midVal = entries.readEncodedKey(pageStart + mid)
      when {
        midVal < key -> lo = mid + 1
        midVal > key -> hi = mid - 1
        else -> return mid
      }
    }
    return lo.inv()
  }
}
