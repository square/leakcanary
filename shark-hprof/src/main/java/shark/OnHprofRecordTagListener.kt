package shark

/**
 * Listener passed in to [StreamingHprofReader.readRecords], gets notified for each
 * [HprofRecordTag] found in the heap dump.
 *
 * Listener implementations are expected to read all bytes corresponding to a given tag from the
 * provided reader before returning.
 */
interface OnHprofRecordTagListener {
  fun onHprofRecord(
    tag: HprofRecordTag,
    /**
     * Length of the record or -1 if there is the length is not known
     */
    length: Long,
    reader: HprofRecordReader
  )

  companion object {
    /**
     * Utility function to create a [OnHprofRecordTagListener] from the passed in [block] lambda
     * instead of using the anonymous `object : OnHprofRecordTagListener` syntax.
     *
     * Usage:
     *
     * ```kotlin
     * val listener = OnHprofRecordTagListener { tag, length, reader ->
     *
     * }
     * ```
     */
    inline operator fun invoke(crossinline block: (HprofRecordTag, Long, HprofRecordReader) -> Unit): OnHprofRecordTagListener =
      object : OnHprofRecordTagListener {
        override fun onHprofRecord(
          tag: HprofRecordTag,
          length: Long,
          reader: HprofRecordReader
        ) {
          block(tag, length, reader)
        }
      }
  }
}