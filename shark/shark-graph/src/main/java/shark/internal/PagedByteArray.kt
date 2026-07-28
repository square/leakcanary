package shark.internal

/**
 * Storage for a list of fixed size entries, split across several [ByteArray] pages so that the
 * total size can exceed the JVM's ~2 GB single-array limit ([Int.MAX_VALUE] bytes).
 *
 * Each entry is [bytesPerEntry] bytes (an id key followed by value bytes). A page holds exactly
 * [entriesPerPage] entries (the last page may hold fewer), and [entriesPerPage] is a power of two
 * so that resolving an entry index to (page, offset) is a shift and a mask. Entries never straddle
 * a page boundary, so a single entry is always fully contained in one page.
 *
 * This is the paged counterpart of the single [ByteArray] backing [UnsortedByteEntries] /
 * [ArraySortedBytesMap], used only when a per-record-type index would otherwise exceed the single
 * array limit. See [PagedByteArrayTimSort] for sorting these in place and [PagedSortedBytesMap] for
 * reading them.
 */
internal class PagedByteArray(
  val bytesPerEntry: Int,
  val entriesPerPage: Int,
  val entryCount: Int,
  val longIdentifiers: Boolean
) {
  init {
    require(entriesPerPage > 0 && entriesPerPage and (entriesPerPage - 1) == 0) {
      "entriesPerPage must be a power of two, was $entriesPerPage"
    }
    require(entryCount >= 0) { "entryCount must be >= 0, was $entryCount" }
  }

  val pageShift: Int = Integer.numberOfTrailingZeros(entriesPerPage)
  val pageMask: Int = entriesPerPage - 1

  private val bytesPerKey = if (longIdentifiers) 8 else 4

  /**
   * The backing pages. All pages but the last hold [entriesPerPage] entries; the last page holds
   * the remainder.
   */
  val pages: Array<ByteArray> = run {
    val pageCount = if (entryCount == 0) 0 else ((entryCount - 1) shr pageShift) + 1
    Array(pageCount) { page ->
      val start = page shl pageShift
      val entriesInPage = minOf(entriesPerPage, entryCount - start)
      ByteArray(entriesInPage * bytesPerEntry)
    }
  }

  fun entriesInPage(page: Int): Int = minOf(entriesPerPage, entryCount - (page shl pageShift))

  /**
   * Reads the id key of the entry at [entryIndex]. Int keys are sign extended, matching the
   * comparison performed by [ArraySortedBytesMap].
   */
  fun readKey(entryIndex: Int): Long {
    val page = pages[entryIndex shr pageShift]
    val base = (entryIndex and pageMask) * bytesPerEntry
    return if (longIdentifiers) page.readLong(base) else page.readInt(base).toLong()
  }

  companion object {
    /**
     * Copies [count] consecutive entries starting at entry [srcStart] in [src] to entry [dstStart]
     * in [dst]. Handles entries that span page boundaries and, when [src] and [dst] are the same
     * instance, overlapping ranges (like [System.arraycopy]).
     */
    fun copyEntries(
      src: PagedByteArray,
      srcStart: Int,
      dst: PagedByteArray,
      dstStart: Int,
      count: Int
    ) {
      if (count == 0) return
      val bytesPerEntry = src.bytesPerEntry
      if (src === dst && dstStart > srcStart) {
        // Overlapping copy towards higher indexes: copy back to front.
        var remaining = count
        while (remaining > 0) {
          val srcEnd = srcStart + remaining - 1
          val dstEnd = dstStart + remaining - 1
          val srcOff = srcEnd and src.pageMask
          val dstOff = dstEnd and dst.pageMask
          val run = minOf(remaining, srcOff + 1, dstOff + 1)
          System.arraycopy(
            src.pages[srcEnd shr src.pageShift], (srcOff + 1 - run) * bytesPerEntry,
            dst.pages[dstEnd shr dst.pageShift], (dstOff + 1 - run) * bytesPerEntry,
            run * bytesPerEntry
          )
          remaining -= run
        }
      } else {
        var done = 0
        while (done < count) {
          val srcIndex = srcStart + done
          val dstIndex = dstStart + done
          val srcOff = srcIndex and src.pageMask
          val dstOff = dstIndex and dst.pageMask
          val run = minOf(count - done, src.entriesPerPage - srcOff, dst.entriesPerPage - dstOff)
          System.arraycopy(
            src.pages[srcIndex shr src.pageShift], srcOff * bytesPerEntry,
            dst.pages[dstIndex shr dst.pageShift], dstOff * bytesPerEntry,
            run * bytesPerEntry
          )
          done += run
        }
      }
    }
  }
}
