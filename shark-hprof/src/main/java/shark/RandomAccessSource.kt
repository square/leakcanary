package shark

import okio.Buffer
import okio.BufferedSource
import okio.Okio
import okio.Source
import okio.Timeout
import java.io.Closeable
import java.io.IOException

interface RandomAccessSource : Closeable {
  @Throws(IOException::class)
  fun read(
    sink: Buffer,
    position: Long,
    byteCount: Long
  ): Long

  fun asStreamingSource(): BufferedSource {
    return Okio.buffer(object : Source {
      var position = 0L

      override fun timeout() = Timeout.NONE

      override fun close() {
        position = -1
      }

      override fun read(
        sink: Buffer,
        byteCount: Long
      ): Long {
        if (position == -1L) {
          throw IOException("Source closed")
        }
        val bytesRead = read(sink, position, byteCount)
        if (bytesRead == 0L) {
          return -1;
        }
        position += bytesRead
        return bytesRead
      }

    })
  }
}
