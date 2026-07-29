package shark

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.source

class FileSourceProvider(private val file: File) : DualSourceProvider {
  override fun openStreamingSource(): BufferedSource = file.inputStream().source().buffer()

  override fun openRandomAccessSource(): RandomAccessSource = FileRandomAccessSource(file)

  /**
   * Reads [file] with positional reads on a single file channel, which several threads can do at
   * the same time: a positional read doesn't use or move the channel position. Each read borrows a
   * scratch array from a pool instead of sharing one, so a single threaded caller keeps reusing the
   * same array.
   *
   * The file is opened once, upfront, so that a caller can delete it as soon as the source is open
   * and keep reading from it.
   */
  private class FileRandomAccessSource(file: File) : RandomAccessSource {

    private val randomAccessFile = RandomAccessFile(file, "r")

    private val channel = randomAccessFile.channel

    private val idleScratchArrays = ConcurrentLinkedQueue<ByteArray>()

    init {
      // The first read needs a scratch array anyway, and allocating it upfront keeps the memory a
      // source uses the same as before it could be read from several threads.
      idleScratchArrays += ByteArray(SCRATCH_ARRAY_SIZE)
    }

    override fun read(
      sink: Buffer,
      position: Long,
      byteCount: Long
    ): Long {
      val scratchArray = idleScratchArrays.poll() ?: ByteArray(SCRATCH_ARRAY_SIZE)
      try {
        val byteCountInt = byteCount.toInt()
        var totalBytesRead = 0
        val maxRead = scratchArray.size
        while (totalBytesRead < byteCountInt) {
          val toRead = min(byteCountInt - totalBytesRead, maxRead)
          val bytesRead = channel.read(
            ByteBuffer.wrap(scratchArray, 0, toRead),
            position + totalBytesRead
          )
          if (bytesRead == -1) {
            check(totalBytesRead != 0) {
              "Did not expect to reach end of file after reading 0 bytes"
            }
            break
          }
          sink.write(scratchArray, 0, bytesRead)
          totalBytesRead += bytesRead
        }
        return totalBytesRead.toLong()
      } finally {
        idleScratchArrays += scratchArray
      }
    }

    override fun close() {
      try {
        // Closes the channel as well.
        randomAccessFile.close()
      } catch (ignored: Throwable) {
        SharkLog.d(ignored) { "Failed to close file, ignoring" }
      }
    }

    companion object {
      private const val SCRATCH_ARRAY_SIZE = 500_000
    }
  }
}
