package shark

import java.util.BitSet
import shark.Read.Companion.PAGE_SIZE

/**
 * One read recorded by a [MetricsDualSourceProvider].
 */
class Read(
  val position: Long,
  val byteCount: Int
) {

  /**
   * The pages this read reads from, empty when it read 0 bytes.
   */
  val pages: LongRange = if (byteCount == 0) {
    LongRange.EMPTY
  } else {
    (position / PAGE_SIZE)..((position + byteCount - 1) / PAGE_SIZE)
  }

  val pageCount: Int = if (byteCount == 0) 0 else (pages.last - pages.first + 1).toInt()

  companion object {
    /**
     * Linux reads files into the page cache one page at a time, so a page is the smallest amount of
     * a heap dump that reading from it can pull off storage, and pages are what stays in memory
     * afterwards. 4096 bytes on every Android device so far, though Android 15 added support for
     * devices with 16 KiB pages. This is a fixed value rather than the page size of whichever
     * machine runs the tests, so that the numbers the tests freeze mean the same thing everywhere.
     */
    const val PAGE_SIZE = 4096
  }
}

val List<Read>.byteCounts: List<Int>
  get() = map { it.byteCount }

/**
 * How many distinct [PAGE_SIZE] byte pages these reads read from. Reads that stay within pages that
 * were already read are nearly free — the page cache serves them from memory — so this is the size
 * of the heap dump that reading actually depends on, and the ceiling of what it can cost in IO. It
 * doesn't depend on the order the reads happen in.
 */
val List<Read>.distinctPagesRead: Int
  get() {
    val pagesRead = BitSet()
    forEach { read ->
      for (page in read.pages) {
        pagesRead.set(page.toInt())
      }
    }
    return pagesRead.cardinality()
  }

/**
 * How many pages these reads go through in total, counting a page again every read that touches it.
 * The gap with [distinctPagesRead] is how much the same pages are read over and over: free when they
 * are still in the page cache, and a read from storage each time when memory pressure evicted them
 * in between, which is what a device that just took a heap dump has plenty of.
 */
val List<Read>.pageReadCount: Int
  get() = sumOf { it.pageCount }
