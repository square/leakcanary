package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.internal.ObjectIdEncoding
import shark.internal.PagedByteArray
import shark.internal.PagedByteArray.Companion.copyEntries

class PagedByteArrayTest {

  /**
   * Brute forces [copyEntries] against a flat reference model for every (srcStart, dstStart, count)
   * within a single paged array, across page sizes that force cross page and overlapping copies.
   */
  @Test fun copyEntriesWithinSameArrayMatchesReference() {
    for (entriesPerPage in intArrayOf(1, 2, 4, 8)) {
      for (entryCount in intArrayOf(1, 2, 5, 8, 9, 17)) {
        for (srcStart in 0 until entryCount) {
          for (dstStart in 0 until entryCount) {
            val maxCount = entryCount - maxOf(srcStart, dstStart)
            for (count in 0..maxCount) {
              val paged = newPagedInts(entriesPerPage, entryCount)
              val model = IntArray(entryCount) { it }
              copyEntries(paged, srcStart, paged, dstStart, count)
              System.arraycopy(model, srcStart, model, dstStart, count)
              assertThat(readInts(paged))
                .`as`("epp=$entriesPerPage n=$entryCount src=$srcStart dst=$dstStart count=$count")
                .containsExactly(*model.toTypedArray())
            }
          }
        }
      }
    }
  }

  @Test fun copyEntriesAcrossArraysMatchesReference() {
    for (entriesPerPage in intArrayOf(1, 2, 4)) {
      val srcCount = 17
      val dstCount = 13
      for (srcStart in 0 until srcCount) {
        for (dstStart in 0 until dstCount) {
          val count = minOf(srcCount - srcStart, dstCount - dstStart)
          val src = newPagedInts(entriesPerPage, srcCount)
          val dst = newPagedInts(entriesPerPage, dstCount, base = 1000)
          val model = IntArray(dstCount) { 1000 + it }
          copyEntries(src, srcStart, dst, dstStart, count)
          System.arraycopy(IntArray(srcCount) { it }, srcStart, model, dstStart, count)
          assertThat(readInts(dst))
            .`as`("epp=$entriesPerPage src=$srcStart dst=$dstStart count=$count")
            .containsExactly(*model.toTypedArray())
        }
      }
    }
  }

  /** Keys are as wide as [ObjectIdEncoding] made them, from a single byte up to all eight. */
  @Test fun readEncodedKeyReadsEveryKeyWidth() {
    for (keyByteCount in 1..8) {
      val span = if (keyByteCount == 8) Long.MAX_VALUE else (1L shl (keyByteCount * 8)) - 1
      val smallestId = -3L
      val ids = longArrayOf(smallestId, smallestId + span / 2, smallestId + span)
      val paged = PagedByteArray(
        bytesPerEntry = keyByteCount + 2, entriesPerPage = 2, entryCount = ids.size,
        idEncoding = ObjectIdEncoding.of(smallestId, smallestId + span), longIdentifiers = true
      )
      assertThat(paged.bytesPerKey).`as`("$keyByteCount byte keys").isEqualTo(keyByteCount)
      ids.forEachIndexed { index, id -> writeEncodedKey(paged, index, id - smallestId) }
      ids.forEachIndexed { index, id ->
        assertThat(paged.idEncoding.decode(paged.readEncodedKey(index)))
          .`as`("$keyByteCount byte keys, entry $index")
          .isEqualTo(id)
      }
    }
  }

  private fun newPagedInts(entriesPerPage: Int, entryCount: Int, base: Int = 0): PagedByteArray {
    val paged = PagedByteArray(
      bytesPerEntry = 4, entriesPerPage = entriesPerPage, entryCount = entryCount,
      idEncoding = ObjectIdEncoding.of(0, 1), longIdentifiers = false
    )
    for (i in 0 until entryCount) {
      writeInt(paged, i, base + i)
    }
    return paged
  }

  private fun readInts(paged: PagedByteArray): Array<Int> =
    Array(paged.entryCount) { readInt(paged, it) }

  private fun writeInt(paged: PagedByteArray, entryIndex: Int, value: Int) {
    val page = paged.pages[entryIndex shr paged.pageShift]
    var pos = (entryIndex and paged.pageMask) * paged.bytesPerEntry
    page[pos++] = (value ushr 24).toByte()
    page[pos++] = (value ushr 16).toByte()
    page[pos++] = (value ushr 8).toByte()
    page[pos] = value.toByte()
  }

  private fun readInt(paged: PagedByteArray, entryIndex: Int): Int {
    val page = paged.pages[entryIndex shr paged.pageShift]
    var pos = (entryIndex and paged.pageMask) * paged.bytesPerEntry
    return (page[pos++].toInt() and 0xff shl 24) or
      (page[pos++].toInt() and 0xff shl 16) or
      (page[pos++].toInt() and 0xff shl 8) or
      (page[pos].toInt() and 0xff)
  }

  private fun writeEncodedKey(paged: PagedByteArray, entryIndex: Int, key: Long) {
    val page = paged.pages[entryIndex shr paged.pageShift]
    var pos = (entryIndex and paged.pageMask) * paged.bytesPerEntry
    var shift = (paged.bytesPerKey - 1) * 8
    while (shift >= 0) {
      page[pos++] = (key ushr shift).toByte()
      shift -= 8
    }
  }
}
