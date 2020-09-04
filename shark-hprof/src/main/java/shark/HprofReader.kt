package shark

import shark.HprofStreamingReader.Companion.createStreamingReaderFor
import kotlin.reflect.KClass

@Deprecated("Replaced by HprofFile")
class HprofReader internal constructor(
  private val hprof: Hprof
) {
  val identifierByteSize: Int
    get() = hprof.header.identifierByteSize

  val startPosition: Long
    get() = hprof.header.recordsPosition.toLong()

  fun readHprofRecords(
    recordTypes: Set<KClass<out HprofRecord>>,
    listener: OnHprofRecordListener
  ) {
    val reader = createStreamingReaderFor(hprof.file, hprof.header)
    reader.readHprofRecords(recordTypes, listener)
  }
}