package shark.internal

/**
 * Writes the value bytes of a single entry, in order. Returned by [SortedBytesMapBuilder.append].
 */
internal interface ByteSubArrayWriter {
  fun writeByte(value: Byte)
  fun writeId(value: Long)
  fun writeInt(value: Int)
  fun writeTruncatedLong(value: Long, byteCount: Int)
  fun writeLong(value: Long)
}

/**
 * Accumulates `id` => `value bytes` entries via [append], then produces a sorted, read only
 * [SortedBytesMap] via [moveToSortedMap]. The total entry count must be known up front so the
 * backing storage can be sized exactly once.
 *
 * @see UnsortedByteEntries single [ByteArray] backed builder.
 * @see PagedUnsortedByteEntries paged builder for indexes larger than [Int.MAX_VALUE] bytes.
 */
internal interface SortedBytesMapBuilder {
  fun append(key: Long): ByteSubArrayWriter
  fun moveToSortedMap(): SortedBytesMap
}

internal object SortedBytesMaps {

  /**
   * The JVM caps a single array at [Int.MAX_VALUE] bytes (some VMs a few bytes below that). An
   * index whose entries fit within this stays on the single [ByteArray] code path, behaving exactly
   * as before paging was introduced.
   */
  const val MAX_SINGLE_ARRAY_BYTES: Long = Int.MAX_VALUE.toLong() - 8

  /**
   * Target byte size of a single page when an index is too large for one array. Comfortably under
   * [MAX_SINGLE_ARRAY_BYTES] so that the in-place sort's temporary pages and rounding to a power of
   * two entries per page stay well below the single array limit.
   */
  const val TARGET_BYTES_PER_PAGE: Long = 1L shl 30  // 1 GB

  /**
   * Visible for testing only: when non-null, [newBuilder] always returns a paged builder using this
   * many entries per page. Lets small heap dumps exercise the paged index path without allocating
   * the multi-GB index that would otherwise be required to cross [MAX_SINGLE_ARRAY_BYTES].
   */
  var forcedEntriesPerPageForTesting: Int? = null

  /**
   * Visible for testing only: when true, [newBuilder] always returns the single [ByteArray] backed
   * builder, reproducing the pre-paging behaviour (and its [NegativeArraySizeException] on indexes
   * larger than [MAX_SINGLE_ARRAY_BYTES]).
   */
  var forceSingleArrayForTesting = false

  fun newBuilder(
    bytesPerValue: Int,
    longIdentifiers: Boolean,
    entryCount: Int
  ): SortedBytesMapBuilder {
    val bytesPerEntry = bytesPerValue + if (longIdentifiers) 8 else 4
    if (forceSingleArrayForTesting) {
      return UnsortedByteEntries(
        bytesPerValue = bytesPerValue,
        longIdentifiers = longIdentifiers,
        initialCapacity = entryCount
      )
    }
    forcedEntriesPerPageForTesting?.let { forcedEntriesPerPage ->
      return PagedUnsortedByteEntries(
        bytesPerValue = bytesPerValue,
        longIdentifiers = longIdentifiers,
        entryCount = entryCount,
        entriesPerPage = forcedEntriesPerPage
      )
    }
    val totalBytes = entryCount.toLong() * bytesPerEntry
    return if (totalBytes <= MAX_SINGLE_ARRAY_BYTES) {
      UnsortedByteEntries(
        bytesPerValue = bytesPerValue,
        longIdentifiers = longIdentifiers,
        initialCapacity = entryCount
      )
    } else {
      PagedUnsortedByteEntries(
        bytesPerValue = bytesPerValue,
        longIdentifiers = longIdentifiers,
        entryCount = entryCount,
        entriesPerPage = entriesPerPage(bytesPerEntry)
      )
    }
  }

  /**
   * Largest power of two number of entries such that a page stays at or below
   * [TARGET_BYTES_PER_PAGE].
   */
  fun entriesPerPage(bytesPerEntry: Int): Int {
    val maxEntries = TARGET_BYTES_PER_PAGE / bytesPerEntry
    return maxOf(1L, java.lang.Long.highestOneBit(maxEntries)).toInt()
  }
}
