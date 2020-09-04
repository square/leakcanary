package shark

import okio.Buffer
import java.io.Closeable

class HprofRandomAccessReader internal constructor(
  hprof: HprofFile
) : Closeable {

  private val buffer =  Buffer()
  private val reader = HprofRecordReader(hprof, buffer)
  private val channel = hprof.file.inputStream().channel

  fun <T> read(
    position: Long,
    byteCount: Long,
    block: HprofRecordReader.() -> T
  ): T {
    println("read at position $position byteCount $byteCount")
    require(byteCount > 0L) {
      "byteCount $byteCount must be > 0"
    }
    var mutablePos = position
    var mutableByteCount = byteCount

    while (mutableByteCount > 0L) {
      val bytesRead = channel.transferTo(mutablePos, mutableByteCount, buffer)
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
    channel.close()
  }
}