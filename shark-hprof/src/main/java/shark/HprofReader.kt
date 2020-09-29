package shark

import shark.StreamingRecordReaderAdapter.Companion.asStreamingRecordReader
import kotlin.reflect.KClass

@Deprecated("Replaced by HprofStreamingReader.readerFor or HprofRandomAccessReader.openReaderFor")
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
    val reader = StreamingHprofReader.readerFor(hprof.file, hprof.header).asStreamingRecordReader()
    reader.readRecords(recordTypes, listener)
  }
}