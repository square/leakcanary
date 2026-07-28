package shark.internal

import shark.internal.aosp.PagedByteArrayTimSort

/**
 * Paged counterpart of [UnsortedByteEntries], used when an index's entries would exceed the JVM's
 * ~2 GB single-array limit. Entries are appended sequentially across the pages of a [PagedByteArray]
 * (pre sized exactly from the known [entryCount], so no page ever grows), then sorted in place and
 * exposed as a [PagedSortedBytesMap].
 *
 * Because the whole logical entry list is sorted in place, the pages end up globally id sorted: page
 * 0 holds the smallest entries, page 1 the next, and so on. The pages are therefore the read map's
 * range partitioned segments, with no separate merge step or copy.
 */
internal class PagedUnsortedByteEntries(
  bytesPerValue: Int,
  longIdentifiers: Boolean,
  entryCount: Int,
  entriesPerPage: Int
) : SortedBytesMapBuilder {

  private val entries = PagedByteArray(
    bytesPerEntry = bytesPerValue + if (longIdentifiers) 8 else 4,
    entriesPerPage = entriesPerPage,
    entryCount = entryCount,
    longIdentifiers = longIdentifiers
  )

  private var assigned = 0

  private val writer = PagedSubArrayWriter()

  override fun append(key: Long): ByteSubArrayWriter {
    val entryIndex = assigned
    assigned++
    val page = entries.pages[entryIndex shr entries.pageShift]
    val base = (entryIndex and entries.pageMask) * entries.bytesPerEntry
    writer.reset(page, base)
    writer.writeId(key)
    return writer
  }

  override fun moveToSortedMap(): SortedBytesMap {
    require(assigned == entries.entryCount) {
      "Expected to append ${entries.entryCount} entries but appended $assigned"
    }
    PagedByteArrayTimSort.sort(entries)
    return PagedSortedBytesMap(entries)
  }

  /**
   * Writes the bytes of a single entry into a target page at a fixed base offset. Mirrors
   * [UnsortedByteEntries.MutableByteSubArray], but the backing array and base offset change on every
   * [reset] (i.e. on every [append]).
   */
  private inner class PagedSubArrayWriter : ByteSubArrayWriter {
    private val longIdentifiers = entries.longIdentifiers
    private val bytesPerEntry = entries.bytesPerEntry

    private var array: ByteArray = entries.pages.firstOrNull() ?: ByteArray(0)
    private var base = 0
    private var subArrayIndex = 0

    fun reset(array: ByteArray, base: Int) {
      this.array = array
      this.base = base
      this.subArrayIndex = 0
    }

    override fun writeByte(value: Byte) {
      val index = subArrayIndex
      subArrayIndex++
      require(index in 0..bytesPerEntry) {
        "Index $index should be between 0 and $bytesPerEntry"
      }
      array[base + index] = value
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
      var pos = base + index
      array[pos++] = (value ushr 24 and 0xff).toByte()
      array[pos++] = (value ushr 16 and 0xff).toByte()
      array[pos++] = (value ushr 8 and 0xff).toByte()
      array[pos] = (value and 0xff).toByte()
    }

    override fun writeTruncatedLong(value: Long, byteCount: Int) {
      val index = subArrayIndex
      subArrayIndex += byteCount
      require(index >= 0 && index <= bytesPerEntry - byteCount) {
        "Index $index should be between 0 and ${bytesPerEntry - byteCount}"
      }
      var pos = base + index
      var shift = (byteCount - 1) * 8
      while (shift >= 8) {
        array[pos++] = (value ushr shift and 0xffL).toByte()
        shift -= 8
      }
      array[pos] = (value and 0xffL).toByte()
    }

    override fun writeLong(value: Long) {
      val index = subArrayIndex
      subArrayIndex += 8
      require(index >= 0 && index <= bytesPerEntry - 8) {
        "Index $index should be between 0 and ${bytesPerEntry - 8}"
      }
      var pos = base + index
      array[pos++] = (value ushr 56 and 0xffL).toByte()
      array[pos++] = (value ushr 48 and 0xffL).toByte()
      array[pos++] = (value ushr 40 and 0xffL).toByte()
      array[pos++] = (value ushr 32 and 0xffL).toByte()
      array[pos++] = (value ushr 24 and 0xffL).toByte()
      array[pos++] = (value ushr 16 and 0xffL).toByte()
      array[pos++] = (value ushr 8 and 0xffL).toByte()
      array[pos] = (value and 0xffL).toByte()
    }
  }
}
