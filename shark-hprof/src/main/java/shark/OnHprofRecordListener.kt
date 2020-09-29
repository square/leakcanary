package shark

/**
 * Listener passed in to [StreamingHprofReader.readRecords], gets notified for each [HprofRecord]
 * found in the heap dump which types is in the set of the recordTypes parameter passed to
 * [StreamingHprofReader.readRecords].
 */
interface OnHprofRecordListener {
  fun onHprofRecord(
    /**
     * The position of the record in the underlying hprof file.
     */
    position: Long,
    record: HprofRecord
  )

  companion object {
    /**
     * Utility function to create a [OnHprofRecordListener] from the passed in [block] lambda
     * instead of using the anonymous `object : OnHprofRecordListener` syntax.
     *
     * Usage:
     *
     * ```kotlin
     * val listener = OnHprofRecordListener { position, record ->
     *
     * }
     * ```
     */
    inline operator fun invoke(crossinline block: (Long, HprofRecord) -> Unit): OnHprofRecordListener =
      object : OnHprofRecordListener {
        override fun onHprofRecord(
          position: Long,
          record: HprofRecord
        ) {
          block(position, record)
        }
      }
  }
}