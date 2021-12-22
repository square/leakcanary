package shark

import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.internal.aosp.ByteArrayTimSort

class ByteArrayTimSortTest {

  @Test fun smallArray1BytePerEntry() {
    val toSort = byteArrayOf(2, 1, 4)

    ByteArrayTimSort.sort(toSort, 0, 3, 1) { _, o1Array, o1Index, o2Array, o2Index ->
      o1Array[o1Index].compareTo(o2Array[o2Index])
    }
    assertThat(toSort).containsExactly(1, 2, 4)
  }

  @Test fun smallArray3BytesPerEntry1ByteKey() {
    val toSort = byteArrayOf(2, 3, 3, 1, 5, 5, 4, 6, 6)

    ByteArrayTimSort.sort(toSort, 3) { entrySize, o1Array, o1Index, o2Array, o2Index ->
      // Sort based on first byte
      o1Array[o1Index * entrySize].compareTo(o2Array[o2Index * entrySize])
    }
    assertThat(toSort).containsExactly(1, 5, 5, 2, 3, 3, 4, 6, 6)
  }

  @Test fun largeRandomArray8BytesPerEntry4BytesKey() {
    val entryCount = 10000
    val entrySize = 8
    val random = Random(Int.MIN_VALUE)
    val librarySorted = random.nextBytes(entryCount * entrySize)

    class Entry(val eightBytes: ByteArray) : Comparable<Entry> {
      override fun compareTo(other: Entry): Int {
        val compared = readInt(eightBytes, 0).compareTo(readInt(other.eightBytes, 0))
        if (compared == 0) {
          return readInt(eightBytes, 4).compareTo(readInt(other.eightBytes, 4))
        }
        return compared
      }
    }

    val androidSorted = arrayOfNulls<Entry>(entryCount)
    for (i in 0 until entryCount) {
      val index = i * entrySize
      androidSorted[i] = Entry(librarySorted.copyOfRange(index, index + entrySize))
    }
    androidSorted.sort()
    val androidSortedAsBytes = ByteArray(entryCount * entrySize)

    for (i in 0 until entryCount) {
      System.arraycopy(
        androidSorted[i]!!.eightBytes, 0, androidSortedAsBytes, i * entrySize, entrySize
      )
    }

    ByteArrayTimSort.sort(librarySorted, entrySize) { entrySize, o1Array, o1Index, o2Array, o2Index ->
      val compared = readInt(o1Array, o1Index * entrySize)
        .compareTo(readInt(o2Array, o2Index * entrySize))
      if (compared == 0) {
        readInt(o1Array, o1Index * entrySize + 4).compareTo(
          readInt(o2Array, o2Index * entrySize + 4)
        )
      }
      compared
    }

    assertThat(librarySorted.asList()).isEqualTo(androidSortedAsBytes.asList())
  }

  fun readInt(
    array: ByteArray,
    index: Int
  ): Int {
    var pos = index
    return (array[pos++] and 0xff shl 24
      or (array[pos++] and 0xff shl 16)
      or (array[pos++] and 0xff shl 8)
      or (array[pos] and 0xff))
  }

  @Suppress("NOTHING_TO_INLINE") // Syntactic sugar.
  private inline infix fun Byte.and(other: Int): Int = toInt() and other
}
