package shark

import java.util.concurrent.atomic.AtomicLong
import okio.Buffer

/**
 * Captures IO read metrics without using much memory.
 *
 * Thread safe: each metric is updated atomically, so reads coming from several threads at the same
 * time still add up. A metric read while reads are in flight is exact for that metric but the
 * metrics aren't a consistent snapshot of each other.
 */
class ConstantMemoryMetricsDualSourceProvider(
  private val realSourceProvider: DualSourceProvider
) : DualSourceProvider {

  private val byteReads = AtomicLong(0)
  private val readCount = AtomicLong(0)

  val randomAccessByteReads: Long
    get() = byteReads.get()

  val randomAccessReadCount: Long
    get() = readCount.get()

  override fun openStreamingSource() = realSourceProvider.openStreamingSource()

  override fun openRandomAccessSource(): RandomAccessSource {
    val randomAccessSource = realSourceProvider.openRandomAccessSource()
    return object : RandomAccessSource {
      override fun read(
        sink: Buffer,
        position: Long,
        byteCount: Long
      ): Long {
        val bytesRead = randomAccessSource.read(sink, position, byteCount)
        byteReads.addAndGet(bytesRead)
        readCount.incrementAndGet()
        return bytesRead
      }

      override fun close() = randomAccessSource.close()
    }
  }
}
