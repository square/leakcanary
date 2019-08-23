package shark.internal.aosp

internal interface ByteArrayComparator {

  /**
   * Indexes are divided by entrySize
   */
  fun compare(
    entrySize: Int,
    o1Array: ByteArray,
    o1Index: Int,
    o2Array: ByteArray,
    o2Index: Int
  ): Int
}