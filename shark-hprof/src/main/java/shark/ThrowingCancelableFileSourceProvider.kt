package shark

import java.io.File
import okio.Buffer
import okio.BufferedSource
import okio.Okio
import okio.Source

/**
 * A [DualSourceProvider] that invokes [throwIfCanceled] before every read, allowing
 * cancellation of IO based work built on top by throwing an exception.
 */
class ThrowingCancelableFileSourceProvider(
  private val file: File,
  private val throwIfCanceled: Runnable
) : DualSourceProvider {

  override fun openStreamingSource(): BufferedSource {
    val realSource = Okio.source(file.inputStream())
    return Okio.buffer(object : Source by realSource {
      override fun read(
        sink: Buffer,
        byteCount: Long
      ): Long {
        throwIfCanceled.run()
        return realSource.read(sink, byteCount)
      }
    })
  }

  override fun openRandomAccessSource(): RandomAccessSource {
    val channel = file.inputStream().channel
    return object : RandomAccessSource {
      override fun read(
        sink: Buffer,
        position: Long,
        byteCount: Long
      ): Long {
        throwIfCanceled.run()
        return channel.transferTo(position, byteCount, sink)
      }

      override fun close() = channel.close()
    }
  }
}
