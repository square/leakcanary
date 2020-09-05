package shark

import okio.Buffer
import java.io.Closeable
import java.io.File

class RandomAccessHprofReader private constructor(
  private val source: RandomAccessSource,
  hprofHeader: HprofHeader
) : Closeable {
  private val buffer = Buffer()
  private val reader = HprofRecordReader(hprofHeader, buffer)

  fun <T> read(
    position: Long,
    byteCount: Long,
    block: HprofRecordReader.() -> T
  ): T {
    require(byteCount > 0L) {
      "byteCount $byteCount must be > 0"
    }
    var mutablePos = position
    var mutableByteCount = byteCount

    while (mutableByteCount > 0L) {
      val bytesRead = source.read(buffer, mutablePos, mutableByteCount)
      check(bytesRead > 0) {
        "Requested $mutableByteCount bytes after reading ${mutablePos - position}, got 0 bytes instead."
      }
      mutablePos += bytesRead
      mutableByteCount -= bytesRead
    }
    return block(reader).apply {
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