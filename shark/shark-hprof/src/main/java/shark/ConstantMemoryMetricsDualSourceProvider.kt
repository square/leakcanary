package shark

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import okio.Buffer

/**
 * Captures IO read metrics without using much memory.
 *
 * Thread safe: each metric is updated atomically, so reads coming from several threads at the same
 * time still add up. A metric read while reads are in flight is exact for that metric but the
 * metrics aren't a consistent snapshot of each other. [randomAccessByteTravel] measures the distance
 * between reads in the order they reach this provider, which with concurrent reads depends on how
 * they interleave.
 */
class ConstantMemoryMetricsDualSourceProvider(
  private val realSourceProvider: DualSourceProvider
) : DualSourceProvider {

  private val byteReads = AtomicLong(0)
  private val readCount = AtomicLong(0)
  private val byteTravel = AtomicLong(0)
  private val lastRandomAccessPosition = AtomicLong(NO_POSITION)
  private val minPosition = AtomicLong(Long.MAX_VALUE)
  private val maxPosition = AtomicLong(Long.MIN_VALUE)

  val randomAccessByteReads: Long
    get() = byteReads.get()

  val randomAccessReadCount: Long
    get() = readCount.get()

  val randomAccessByteTravel: Long
    get() = byteTravel.get()

  val byteTravelRange: Long
    get() = if (readCount.get() == 0L) 0L else maxPosition.get() - minPosition.get()

  private fun updateRandomAccessStatsOnRead(
    position: Long,
    bytesRead: Long
  ) {
    byteReads.addAndGet(bytesRead)
    readCount.incrementAndGet()
    val previousPosition = lastRandomAccessPosition.getAndSet(position)
    if (previousPosition != NO_POSITION) {
      byteTravel.addAndGet((position - previousPosition).absoluteValue)
    }
    minPosition.accumulateAndGet(position) { lowest, newPosition -> min(lowest, newPosition) }
    maxPosition.accumulateAndGet(position) { highest, newPosition -> max(highest, newPosition) }
  }

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
        updateRandomAccessStatsOnRead(position, bytesRead)
        return bytesRead
      }

      override fun close() = randomAccessSource.close()
    }
  }

  private companion object {
    const val NO_POSITION = -1L
  }
}
