package leakcanary

import okio.BufferedSource
import java.nio.channels.FileChannel

/**
 * A [HprofReader] that can be moved ([moveTo]) to a new position.
 */
class SeekableHprofReader(
  private val channel: FileChannel,
  source: BufferedSource,
  startPosition: Long,
  idSize: Int
) : HprofReader(source, startPosition, idSize) {

  fun moveTo(newPosition: Long) {
    if (position == newPosition) {
      return
    }
    source.buffer.clear()
    channel.position(newPosition)
    position = newPosition
  }

  fun reset() {
    moveTo(startPosition)
  }
}