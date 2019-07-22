package shark

interface OnHprofRecordListener {
  fun onHprofRecord(
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