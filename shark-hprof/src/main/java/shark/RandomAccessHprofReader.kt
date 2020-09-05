package shark

import okio.Buffer
import java.io.Closeable
import java.io.File

/**
 * Reads records in a Hprof source, one at a time with a specific position and size.
 * Call [openReaderFor] to obtain a new instance.
 */
class RandomAccessHprofReader private constructor(
  private val source: RandomAccessSource,
  hprofHeader: HprofHeader
) : Closeable {
  private val buffer = Buffer()
  private val reader = HprofRecordReader(hprofHeader, buffer)

  /**
   * Loads [recordSize] bytes at [recordPosition] into the buffer that backs [HprofRecordReader]
   * then calls [withRecordReader] with that reader as a receiver. [withRecordReader] is expected
   * to use the receiver reader to read one record of exactly [recordSize] bytes.
   * @return the results from [withRecordReader]
   */
  fun <T> readRecord(
    recordPosition: Long,
    recordSize: Long,
    withRecordReader: HprofRecordReader.() -> T
  ): T {
    require(recordSize > 0L) {
      "recordSize $recordSize must be > 0"
    }
    var mutablePos = recordPosition
    var mutableByteCount = recordSize

    while (mutableByteCount > 0L) {
      val bytesRead = source.read(buffer, mutablePos, mutableByteCount)
      check(bytesRead > 0) {
        "Requested $mutableByteCount bytes after reading ${mutablePos - recordPosition}, got 0 bytes instead."
      }
      mutablePos += bytesRead
      mutableByteCount -= bytesRead
    }
    return withRecordReader(reader).apply {
      check(buffer.size() == 0L) {
        "Buffer not fully consumed: ${buffer.size()} bytes left"
      }
    }
  }

  override fun close() {
    source.close()
  }

  companion object {

    fun openReaderFor(
      hprofFile: File,
      hprofHeader: HprofHeader = HprofHeader.parseHeaderOf(hprofFile)
    ): RandomAccessHprofReader {
      val sourceProvider = FileSourceProvider(hprofFile)
      return openReaderFor(sourceProvider, hprofHeader)
    }

    fun openReaderFor(
      hprofSourceProvider: RandomAccessSourceProvider,
      hprofHeader: HprofHeader = hprofSourceProvider.openRandomAccessSource()
          .use { HprofHeader.parseHeaderOf(it.asStreamingSource()) }
    ): RandomAccessHprofReader {
      return RandomAccessHprofReader(hprofSourceProvider.openRandomAccessSource(), hprofHeader)
    }
  }
}