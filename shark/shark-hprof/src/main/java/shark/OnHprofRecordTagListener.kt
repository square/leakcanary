package shark

/**
 * Listener passed in to [StreamingHprofReader.readRecords], gets notified for each
 * [HprofRecordTag] found in the heap dump.
 *
 * Listener implementations are expected to read all bytes corresponding to a given tag from the
 * provided reader before returning.
 */
fun interface OnHprofRecordTagListener {
  fun onHprofRecord(
    tag: HprofRecordTag,
    /**
     * Length of the record or -1 if there is the length is not known
     */
    length: Long,
    reader: HprofRecordReader
  )
}
