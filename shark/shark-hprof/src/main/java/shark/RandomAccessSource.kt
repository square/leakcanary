package shark

import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.Closeable
import java.io.IOException

interface RandomAccessSource : Closeable {
  /**
   * Reads up to [byteCount] bytes starting at [position], writes them to [sink] and returns the
   * number of bytes read.
   *
   * Implementations should support being called concurrently from several threads: every read
   * states the position it reads from, so implementations shouldn't rely on a shared read position
   * or share a scratch buffer between reads. [RandomAccessHprofReader] relies on this to let
   * several threads read from a heap dump at the same time.
   */
  @Throws(IOException::class)
  fun read(
    sink: Buffer,
    position: Long,
    byteCount: Long
  ): Long

  /**
   * Returns a [BufferedSource] that reads this source from the start, keeping track of its own
   * read position. The returned source is not thread safe.
   */
  fun asStreamingSource(): BufferedSource {
    return (object : Source {
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
          return -1
        }
        position += bytesRead
        return bytesRead
      }
    }).buffer()
  }
}
