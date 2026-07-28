/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("MultiLineIfElse", "NoMultipleSpaces", "MaximumLineLength")

package shark.internal.aosp

import kotlin.math.min
import shark.internal.PagedByteArray
import shark.internal.PagedByteArray.Companion.copyEntries

/*
This is the paged counterpart of [ByteArrayTimSort]: the same AOSP TimSort, but sorting a
[PagedByteArray] in place instead of a single byte array, so that the sorted data can exceed the
JVM's ~2 GB single-array limit. Entries are compared by their id key (the first bytes of each
entry) and the temporary storage is itself a [PagedByteArray], so neither the data nor the scratch
buffer is bound by the single-array limit.

Adapted from https://android.googlesource.com/platform/libcore/+/jb-mr2-release/luni/src/main/java/java/util/TimSort.java
*/
@Suppress("detekt.complexity", "detekt.style")
internal class PagedByteArrayTimSort private constructor(
  private val a: PagedByteArray
) {
  private var minGallop = MIN_GALLOP

  /** Temp storage for merges. */
  private var tmp: PagedByteArray = newScratch(
    if (a.entryCount < 2 * INITIAL_TMP_STORAGE_LENGTH) {
      a.entryCount ushr 1
    } else {
      INITIAL_TMP_STORAGE_LENGTH
    }
  )

  /** Single entry scratch buffers, reused for binary insertion sort and entry swaps. */
  private val pivot = newScratch(1)
  private val swapBuffer = newScratch(1)

  private var stackSize = 0
  private val runBase: IntArray
  private val runLen: IntArray

  init {
    val len = a.entryCount
    val stackLen = when {
        len < 120 -> 5
        len < 1542 -> 10
        len < 119151 -> 19
        else -> 40
    }
    runBase = IntArray(stackLen)
    runLen = IntArray(stackLen)
  }

  private fun newScratch(entryCount: Int): PagedByteArray =
    PagedByteArray(a.bytesPerEntry, a.entriesPerPage, maxOf(1, entryCount), a.longIdentifiers)

  private fun pushRun(
    runBase: Int,
    runLen: Int
  ) {
    this.runBase[stackSize] = runBase
    this.runLen[stackSize] = runLen
    stackSize++
  }

  // Fixed with http://www.envisage-project.eu/proving-android-java-and-python-sorting-algorithm-is-broken-and-how-to-fix-it/
  private fun mergeCollapse() {
    while (stackSize > 1) {
      var n = stackSize - 2
      if (n >= 1 && runLen[n - 1] <= runLen[n] + runLen[n + 1] || n >= 2 && runLen[n - 2] <= runLen[n] + runLen[n - 1]) {
        if (runLen[n - 1] < runLen[n + 1])
          n--
      } else if (runLen[n] > runLen[n + 1]) {
        break // Invariant is established
      }
      mergeAt(n)
    }
  }

  private fun mergeForceCollapse() {
    while (stackSize > 1) {
      var n = stackSize - 2
      if (n > 0 && runLen[n - 1] < runLen[n + 1])
        n--
      mergeAt(n)
    }
  }

  private fun mergeAt(i: Int) {
    var base1 = runBase[i]
    var len1 = runLen[i]
    val base2 = runBase[i + 1]
    var len2 = runLen[i + 1]
    runLen[i] = len1 + len2
    if (i == stackSize - 3) {
      runBase[i + 1] = runBase[i + 2]
      runLen[i + 1] = runLen[i + 2]
    }
    stackSize--
    val k = gallopRight(a, base2, a, base1, len1, 0)
    base1 += k
    len1 -= k
    if (len1 == 0)
      return
    len2 = gallopLeft(a, base1 + len1 - 1, a, base2, len2, len2 - 1)
    if (len2 == 0)
      return
    if (len1 <= len2)
      mergeLo(base1, len1, base2, len2)
    else
      mergeHi(base1, len1, base2, len2)
  }

  private fun mergeLo(
    base1: Int,
    len1: Int,
    base2: Int,
    len2: Int
  ) {
    var len1 = len1
    var len2 = len2
    val a = this.a
    val tmp = ensureCapacity(len1)
    copyEntries(a, base1, tmp, 0, len1)
    var cursor1 = 0       // Indexes into tmp array
    var cursor2 = base2   // Indexes into a
    var dest = base1      // Indexes into a
    copyEntries(a, cursor2, a, dest, 1)
    dest++
    cursor2++

    if (--len2 == 0) {
      copyEntries(tmp, cursor1, a, dest, len1)
      return
    }
    if (len1 == 1) {
      copyEntries(a, cursor2, a, dest, len2)
      copyEntries(tmp, cursor1, a, dest + len2, 1) // Last elt of run 1 to end of merge
      return
    }
    var minGallop = this.minGallop
    outer@ while (true) {
      var count1 = 0 // Number of times in a row that first run won
      var count2 = 0 // Number of times in a row that second run won
      do {
        if (compare(a, cursor2, tmp, cursor1) < 0) {
          copyEntries(a, cursor2, a, dest, 1)
          dest++
          cursor2++
          count2++
          count1 = 0
          if (--len2 == 0)
            break@outer
        } else {
          copyEntries(tmp, cursor1, a, dest, 1)
          dest++
          cursor1++
          count1++
          count2 = 0
          if (--len1 == 1)
            break@outer
        }
      } while (count1 or count2 < minGallop)
      do {
        count1 = gallopRight(a, cursor2, tmp, cursor1, len1, 0)
        if (count1 != 0) {
          copyEntries(tmp, cursor1, a, dest, count1)
          dest += count1
          cursor1 += count1
          len1 -= count1
          if (len1 <= 1)
            break@outer
        }
        copyEntries(a, cursor2, a, dest, 1)
        dest++
        cursor2++
        if (--len2 == 0)
          break@outer
        count2 = gallopLeft(tmp, cursor1, a, cursor2, len2, 0)
        if (count2 != 0) {
          copyEntries(a, cursor2, a, dest, count2)
          dest += count2
          cursor2 += count2
          len2 -= count2
          if (len2 == 0)
            break@outer
        }
        copyEntries(tmp, cursor1, a, dest, 1)
        dest++
        cursor1++
        if (--len1 == 1)
          break@outer
        minGallop--
      } while ((count1 >= MIN_GALLOP) or (count2 >= MIN_GALLOP))
      if (minGallop < 0)
        minGallop = 0
      minGallop += 2  // Penalize for leaving gallop mode
    }  // End of "outer" loop
    this.minGallop = if (minGallop < 1) 1 else minGallop  // Write back to field
    when (len1) {
        1 -> {
          copyEntries(a, cursor2, a, dest, len2)
          copyEntries(tmp, cursor1, a, dest + len2, 1) //  Last elt of run 1 to end of merge
        }
        0 -> {
          throw IllegalArgumentException(
            "Comparison method violates its general contract!"
          )
        }
        else -> {
          copyEntries(tmp, cursor1, a, dest, len1)
        }
    }
  }

  private fun mergeHi(
    base1: Int,
    len1: Int,
    base2: Int,
    len2: Int
  ) {
    var len1 = len1
    var len2 = len2
    val a = this.a
    val tmp = ensureCapacity(len2)
    copyEntries(a, base2, tmp, 0, len2)
    var cursor1 = base1 + len1 - 1  // Indexes into a
    var cursor2 = len2 - 1          // Indexes into tmp array
    var dest = base2 + len2 - 1     // Indexes into a
    copyEntries(a, cursor1, a, dest, 1)
    dest--
    cursor1--
    if (--len1 == 0) {
      copyEntries(tmp, 0, a, dest - (len2 - 1), len2)
      return
    }
    if (len2 == 1) {
      dest -= len1
      cursor1 -= len1
      copyEntries(a, cursor1 + 1, a, dest + 1, len1)
      copyEntries(tmp, cursor2, a, dest, 1)
      return
    }
    var minGallop = this.minGallop
    outer@ while (true) {
      var count1 = 0 // Number of times in a row that first run won
      var count2 = 0 // Number of times in a row that second run won
      do {
        if (compare(tmp, cursor2, a, cursor1) < 0) {
          copyEntries(a, cursor1, a, dest, 1)
          dest--
          cursor1--
          count1++
          count2 = 0
          if (--len1 == 0)
            break@outer
        } else {
          copyEntries(tmp, cursor2, a, dest, 1)
          dest--
          cursor2--
          count2++
          count1 = 0
          if (--len2 == 1)
            break@outer
        }
      } while (count1 or count2 < minGallop)
      do {
        count1 = len1 - gallopRight(tmp, cursor2, a, base1, len1, len1 - 1)
        if (count1 != 0) {
          dest -= count1
          cursor1 -= count1
          len1 -= count1
          copyEntries(a, cursor1 + 1, a, dest + 1, count1)
          if (len1 == 0)
            break@outer
        }
        copyEntries(tmp, cursor2, a, dest, 1)
        dest--
        cursor2--
        if (--len2 == 1)
          break@outer
        count2 = len2 - gallopLeft(a, cursor1, tmp, 0, len2, len2 - 1)
        if (count2 != 0) {
          dest -= count2
          cursor2 -= count2
          len2 -= count2
          copyEntries(tmp, cursor2 + 1, a, dest + 1, count2)
          if (len2 <= 1)
            break@outer
        }
        copyEntries(a, cursor1, a, dest, 1)
        dest--
        cursor1--
        if (--len1 == 0)
          break@outer
        minGallop--
      } while ((count1 >= MIN_GALLOP) or (count2 >= MIN_GALLOP))
      if (minGallop < 0)
        minGallop = 0
      minGallop += 2  // Penalize for leaving gallop mode
    }  // End of "outer" loop
    this.minGallop = if (minGallop < 1) 1 else minGallop  // Write back to field
    when (len2) {
        1 -> {
          dest -= len1
          cursor1 -= len1
          copyEntries(a, cursor1 + 1, a, dest + 1, len1)
          copyEntries(tmp, cursor2, a, dest, 1) // Move first elt of run2 to front of merge
        }
        0 -> {
          throw IllegalArgumentException(
            "Comparison method violates its general contract!"
          )
        }
        else -> {
          copyEntries(tmp, 0, a, dest - (len2 - 1), len2)
        }
    }
  }

  private fun ensureCapacity(minCapacity: Int): PagedByteArray {
    if (tmp.entryCount < minCapacity) {
      // Compute smallest power of 2 > minCapacity
      var newSize = minCapacity
      newSize = newSize or (newSize shr 1)
      newSize = newSize or (newSize shr 2)
      newSize = newSize or (newSize shr 4)
      newSize = newSize or (newSize shr 8)
      newSize = newSize or (newSize shr 16)
      newSize++
      newSize = if (newSize < 0)
        minCapacity
      else
        min(newSize, a.entryCount ushr 1)
      tmp = newScratch(newSize)
    }
    return tmp
  }

  private fun binarySort(
    lo: Int,
    hi: Int,
    start: Int
  ) {
    var start = start
    if (start == lo)
      start++
    while (start < hi) {
      copyEntries(a, start, pivot, 0, 1)
      // Set left (and right) to the index where a[start] (pivot) belongs
      var left = lo
      var right = start
      while (left < right) {
        val mid = (left + right).ushr(1)
        if (compare(pivot, 0, a, mid) < 0)
          right = mid
        else
          left = mid + 1
      }
      // Slide elements over to make room for pivot, then drop pivot in at left.
      val n = start - left // The number of elements to move
      if (n > 0) {
        copyEntries(a, left, a, left + 1, n)
      }
      copyEntries(pivot, 0, a, left, 1)
      start++
    }
  }

  private fun countRunAndMakeAscending(
    lo: Int,
    hi: Int
  ): Int {
    var runHi = lo + 1
    if (runHi == hi)
      return 1
    val comparison = compare(a, runHi, a, lo)
    runHi++
    if (comparison < 0) { // Descending
      while (runHi < hi && compare(a, runHi, a, runHi - 1) < 0)
        runHi++
      reverseRange(lo, runHi)
    } else {              // Ascending
      while (runHi < hi && compare(a, runHi, a, runHi - 1) >= 0)
        runHi++
    }
    return runHi - lo
  }

  private fun reverseRange(
    lo: Int,
    hi: Int
  ) {
    var lo = lo
    var hi = hi
    hi--
    while (lo < hi) {
      copyEntries(a, lo, swapBuffer, 0, 1)
      copyEntries(a, hi, a, lo, 1)
      copyEntries(swapBuffer, 0, a, hi, 1)
      lo++
      hi--
    }
  }

  private fun gallopLeft(
    key: PagedByteArray,
    keyIndex: Int,
    arr: PagedByteArray,
    base: Int,
    len: Int,
    hint: Int
  ): Int {
    var lastOfs = 0
    var ofs = 1
    if (compare(key, keyIndex, arr, base + hint) > 0) {
      val maxOfs = len - hint
      while (ofs < maxOfs && compare(key, keyIndex, arr, base + hint + ofs) > 0) {
        lastOfs = ofs
        ofs = ofs * 2 + 1
        if (ofs <= 0) // int overflow
          ofs = maxOfs
      }
      if (ofs > maxOfs)
        ofs = maxOfs
      lastOfs += hint
      ofs += hint
    } else { // key <= arr[base + hint]
      val maxOfs = hint + 1
      while (ofs < maxOfs && compare(key, keyIndex, arr, base + hint - ofs) <= 0) {
        lastOfs = ofs
        ofs = ofs * 2 + 1
        if (ofs <= 0) // int overflow
          ofs = maxOfs
      }
      if (ofs > maxOfs)
        ofs = maxOfs
      val tmp = lastOfs
      lastOfs = hint - ofs
      ofs = hint - tmp
    }
    lastOfs++
    while (lastOfs < ofs) {
      val m = lastOfs + (ofs - lastOfs).ushr(1)
      if (compare(key, keyIndex, arr, base + m) > 0)
        lastOfs = m + 1  // arr[base + m] < key
      else
        ofs = m          // key <= arr[base + m]
    }
    return ofs
  }

  private fun gallopRight(
    key: PagedByteArray,
    keyIndex: Int,
    arr: PagedByteArray,
    base: Int,
    len: Int,
    hint: Int
  ): Int {
    var ofs = 1
    var lastOfs = 0
    if (compare(key, keyIndex, arr, base + hint) < 0) {
      val maxOfs = hint + 1
      while (ofs < maxOfs && compare(key, keyIndex, arr, base + hint - ofs) < 0) {
        lastOfs = ofs
        ofs = ofs * 2 + 1
        if (ofs <= 0) // int overflow
          ofs = maxOfs
      }
      if (ofs > maxOfs)
        ofs = maxOfs
      val tmp = lastOfs
      lastOfs = hint - ofs
      ofs = hint - tmp
    } else { // arr[base + hint] <= key
      val maxOfs = len - hint
      while (ofs < maxOfs && compare(key, keyIndex, arr, base + hint + ofs) >= 0) {
        lastOfs = ofs
        ofs = ofs * 2 + 1
        if (ofs <= 0) // int overflow
          ofs = maxOfs
      }
      if (ofs > maxOfs)
        ofs = maxOfs
      lastOfs += hint
      ofs += hint
    }
    lastOfs++
    while (lastOfs < ofs) {
      val m = lastOfs + (ofs - lastOfs).ushr(1)
      if (compare(key, keyIndex, arr, base + m) < 0)
        ofs = m          // key < arr[base + m]
      else
        lastOfs = m + 1  // arr[base + m] <= key
    }
    return ofs
  }

  private fun compare(
    aArray: PagedByteArray,
    aIndex: Int,
    bArray: PagedByteArray,
    bIndex: Int
  ): Int = aArray.readKey(aIndex).compareTo(bArray.readKey(bIndex))

  private fun doSort(
    lo: Int,
    hi: Int
  ) {
    var lo = lo
    var nRemaining = hi - lo
    if (nRemaining < 2)
      return   // Arrays of size 0 and 1 are always sorted
    if (nRemaining < MIN_MERGE) {
      val initRunLen = countRunAndMakeAscending(lo, hi)
      binarySort(lo, hi, lo + initRunLen)
      return
    }
    val minRun = minRunLength(nRemaining)
    do {
      var runLen = countRunAndMakeAscending(lo, hi)
      if (runLen < minRun) {
        val force = if (nRemaining <= minRun) nRemaining else minRun
        binarySort(lo, lo + force, lo + runLen)
        runLen = force
      }
      pushRun(lo, runLen)
      mergeCollapse()
      lo += runLen
      nRemaining -= runLen
    } while (nRemaining != 0)
    mergeForceCollapse()
  }

  companion object {
    private const val MIN_MERGE = 32
    private const val MIN_GALLOP = 7
    private const val INITIAL_TMP_STORAGE_LENGTH = 256

    fun sort(a: PagedByteArray) {
      val hi = a.entryCount
      if (hi < 2) return
      PagedByteArrayTimSort(a).doSort(0, hi)
    }

    private fun minRunLength(n: Int): Int {
      var n = n
      var r = 0      // Becomes 1 if any 1 bits are shifted off
      while (n >= MIN_MERGE) {
        r = r or (n and 1)
        n = n shr 1
      }
      return n + r
    }
  }
}
