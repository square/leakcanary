package shark

import java.io.File
import okio.Buffer
import okio.BufferedSource
import okio.Okio

class FileSourceProvider(private val file: File) : DualSourceProvider {
  override fun openStreamingSource(): BufferedSource = Okio.buffer(Okio.source(file.inputStream()))

  override fun openRandomAccessSource(): RandomAccessSource {
    val channel = file.inputStream().channel
    return object : RandomAccessSource {
      override fun read(
        sink: Buffer,
        position: Long,
        byteCount: Long
      ) = channel.transferTo(position, byteCount, sink)

      override fun close() = channel.close()
    }
  }
}
