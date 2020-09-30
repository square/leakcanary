package shark

import okio.Buffer
import kotlin.math.absoluteValue

/**
 * Captures IO read metrics without using much memory.
 */
class ConstantMemoryMetricsDualSourceProvider(
  private val realSourceProvider: DualSourceProvider
) : DualSourceProvider {

  var randomAccessByteReads = 0L
    internal set

  var randomAccessReadCount = 0L
    internal set

  var randomAccessByteTravel = 0L
    internal set

  private var lastRandomAccessPosition = -1L
  private var minPosition = -1L
  private var maxPosition = -1L

  private fun updateRandomAccessStatsOnRead(position: Long, bytesRead: Long) {
    randomAccessByteReads += bytesRead
    randomAccessReadCount++
    if (lastRandomAccessPosition != -1L) {
      randomAccessByteTravel += (position - lastRandomAccessPosition).absoluteValue
      minPosition = minPosition.coerceAtMost(position)
      maxPosition = maxPosition.coerceAtLeast(position)
    } else {
      minPosition = position
      maxPosition = position
    }


    lastRandomAccessPosition = position
  }

  val byteTravelRange
      get() = (maxPosition - minPosition)

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

}