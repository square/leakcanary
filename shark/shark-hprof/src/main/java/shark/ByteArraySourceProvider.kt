package shark

import java.io.IOException
import okio.Buffer
import okio.BufferedSource

class ByteArraySourceProvider(private val byteArray: ByteArray) : DualSourceProvider {
  override fun openStreamingSource(): BufferedSource = Buffer().apply { write(byteArray) }

  override fun openRandomAccessSource(): RandomAccessSource {
    return object : RandomAccessSource {

      var closed = false

      override fun read(
        sink: Buffer,
        position: Long,
        byteCount: Long
      ): Long {
        if (closed) {
          throw IOException("Source closed")
        }
        val maxByteCount = byteCount.coerceAtMost(byteArray.size - position)
        sink.write(byteArray, position.toInt(), maxByteCount.toInt())
        return maxByteCount
      }

      override fun close() {
        closed = true
      }
    }
  }
}
