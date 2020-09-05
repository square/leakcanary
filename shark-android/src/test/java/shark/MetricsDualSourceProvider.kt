package shark

import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer
import okio.source
import java.io.File

class MetricsDualSourceProvider(val file: File) : DualSourceProvider {

  val sourcesMetrics = mutableListOf<MutableList<Int>>()

  override fun openStreamingSource(): BufferedSource {
    val sourceMetrics = mutableListOf<Int>()
    sourcesMetrics += sourceMetrics
    val fileSource = file.source()
    return object : Source {
      override fun read(
        sink: Buffer,
        byteCount: Long
      ): Long {
        val bytesRead = fileSource.read(sink, byteCount)
        sourceMetrics += if (bytesRead >= 0) bytesRead.toInt() else 0
        return bytesRead
      }

      override fun close() = fileSource.close()

      override fun timeout() = fileSource.timeout()

    }.buffer()
  }

  override fun openRandomAccessSource(): RandomAccessSource {
    val sourceMetrics = mutableListOf<Int>()
    sourcesMetrics += sourceMetrics
    val channel = file.inputStream().channel
    return object : RandomAccessSource {
      override fun read(
        sink: Buffer,
        position: Long,
        byteCount: Long
      ): Long {
        val bytesRead = channel.transferTo(position, byteCount, sink)
        sourceMetrics += bytesRead.toInt()
        return bytesRead
      }

      override fun close() = channel.close()
    }
  }

}