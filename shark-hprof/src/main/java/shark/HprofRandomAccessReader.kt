package shark

import okio.Buffer
import java.io.Closeable
import java.io.File

class HprofRandomAccessReader private constructor(
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

    fun openRandomAccessReaderFor(
      hprofFile: File,
      header: HprofHeader = HprofHeader.parseHeaderOf(hprofFile)
    ): HprofRandomAccessReader {
      val channel = hprofFile.inputStream().channel
      val source = object : RandomAccessSource {
        override fun read(
          sink: Buffer,
          position: Long,
          byteCount: Long
        ) = channel.transferTo(position, byteCount, sink)

        override fun close() = channel.close()
      }
      return HprofRandomAccessReader(source, header)
    }

    fun createRandomAccessReaderFor(
      hprofSource: RandomAccessSource,
      hprofHeader: HprofHeader
    ): HprofRandomAccessReader {
      return HprofRandomAccessReader(hprofSource, hprofHeader)
    }
  }
}