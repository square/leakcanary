package shark

/**
 * Listener passed in to [StreamingHprofReader.readRecords], gets notified for each [HprofRecord]
 * found in the heap dump which types is in the set of the recordTypes parameter passed to
 * [StreamingHprofReader.readRecords].
 *
 * This is a functional interface with which you can create a [OnHprofRecordListener] from a lambda.
 */
fun interface OnHprofRecordListener {
  fun onHprofRecord(
    /**
     * The position of the record in the underlying hprof file.
     */
    position: Long,
    record: HprofRecord
  )
}