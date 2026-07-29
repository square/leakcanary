package shark

import java.io.File
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer

/**
 * Records every read performed on the sources it hands out, as one list of [Read] per source, in the
 * order the sources were opened.
 */
class MetricsDualSourceProvider(
  private val realSourceProvider: DualSourceProvider
) : DualSourceProvider {

  constructor(file: File) : this(FileSourceProvider(file))

  val sourcesMetrics = mutableListOf<MutableList<Read>>()

  override fun openStreamingSource(): BufferedSource {
    val sourceMetrics = mutableListOf<Read>()
    sourcesMetrics += sourceMetrics
    val fileSource = realSourceProvider.openStreamingSource()
    // A streaming source reads from the start of the file and moves forward, so we can keep track of
    // where each read reads from even though the reader never says.
    var position = 0L
    return object : Source {
      override fun read(
        sink: Buffer,
        byteCount: Long
      ): Long {
        val bytesRead = fileSource.read(sink, byteCount)
        val bytesReadOrZero = if (bytesRead >= 0) bytesRead.toInt() else 0
        sourceMetrics += Read(position, bytesReadOrZero)
        position += bytesReadOrZero
        return bytesRead
      }

      override fun close() = fileSource.close()

      override fun timeout() = fileSource.timeout()
    }.buffer()
  }

  override fun openRandomAccessSource(): RandomAccessSource {
    val sourceMetrics = mutableListOf<Read>()
    sourcesMetrics += sourceMetrics
    val randomAccessSource = realSourceProvider.openRandomAccessSource()
    return object : RandomAccessSource {
      override fun read(
        sink: Buffer,
        position: Long,
        byteCount: Long
      ): Long {
        val bytesRead = randomAccessSource.read(sink, position, byteCount)
        sourceMetrics += Read(position, bytesRead.toInt())
        return bytesRead
      }

      override fun close() = randomAccessSource.close()
    }
  }
}
