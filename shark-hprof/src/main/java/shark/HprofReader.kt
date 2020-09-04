package shark

import kotlin.reflect.KClass

@Deprecated("Replaced by HprofFile")
class HprofReader internal constructor(
  private val hprofFile: HprofFile
) {
  val identifierByteSize: Int
    get() = hprofFile.identifierByteSize

  val startPosition: Long
    get() = hprofFile.recordsPosition

  fun readHprofRecords(
    recordTypes: Set<KClass<out HprofRecord>>,
    listener: OnHprofRecordListener
  ) {
    hprofFile.streamingReader().readHprofRecordsAsStream(recordTypes, listener)
  }
}