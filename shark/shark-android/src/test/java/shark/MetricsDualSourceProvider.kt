package shark

import java.io.File
import java.util.Collections.synchronizedList
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer

/**
 * Records every read performed on the sources it hands out, as one list of [Read] per source, in the
 * order the sources were opened.
 *
 * A random access source can be read from several threads at the same time, so the recording is
 * synchronized: what a read costs is worth measuring for a concurrent reader too, and dropping reads
 * on the floor would make these metrics quietly wrong rather than loudly broken. Reads land in the
 * list in the order they complete, so with concurrent reads the *order* of a source's reads is an
 * interleaving rather than a sequence. [distinctPagesRead], [pageReadCount] and [byteCounts] sums
 * don't depend on that order; an assertion on the exact list of reads only makes sense for a source
 * that one thread reads.
 */
class MetricsDualSourceProvider(
  private val realSourceProvider: DualSourceProvider
) : DualSourceProvider {

  constructor(file: File) : this(FileSourceProvider(file))

  val sourcesMetrics: MutableList<MutableList<Read>> = synchronizedList(mutableListOf())

  override fun openStreamingSource(): BufferedSource {
    val sourceMetrics = mutableListOf<Read>()
    sourcesMetrics += sourceMetrics
    val fileSource = realSourceProvider.openStreamingSource()
    // A streaming source is read by a single thread, from the start of the file forward, so we can
    // keep track of where each read reads from even though the reader never says.
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
    val sourceMetrics = synchronizedList(mutableListOf<Read>())
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
