package shark

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
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

    /**
     * Scratch arrays are 500 KB each, so the pool is capped: a spike of parallel reads shouldn't
     * leave a scratch array per thread behind once the reads are done. Reads beyond that cap
     * allocate an array and drop it when they're done.
     */
    private val idleScratchArrays = ArrayBlockingQueue<ByteArray>(MAX_IDLE_SCRATCH_ARRAYS)

    init {
      // The first read needs a scratch array anyway, and allocating it upfront keeps the memory a
      // source uses the same as before it could be read from several threads.
      idleScratchArrays.offer(ByteArray(SCRATCH_ARRAY_SIZE))
    }

    /**
     * This used to be [RandomAccessFile.seek] then [RandomAccessFile.read], which reads from the
     * file position that seek() just set. Two threads reading at once would then race on that one
     * shared position and read each other's bytes. A positional channel read takes the position as
     * a parameter instead: it maps to pread() (ReadFile with an OVERLAPPED offset on Windows),
     * leaves the channel position untouched, and doesn't lock it, except on Windows where
     * FileChannel does hold a lock around the seek and read it has to do instead. Otherwise the two
     * behave the same: both return -1 at the end of the file, both may come back with fewer bytes
     * than asked for (hence the loop below), and neither one is affected by what the streaming
     * source does, as that reads through a separate file descriptor.
     *
     * Two differences worth knowing about, neither of which changes what a read returns:
     *
     * - Reading into a heap array means the JDK copies through a direct ByteBuffer it caches per
     *   thread (see sun.nio.ch.Util.getTemporaryDirectBuffer). That cache holds a buffer as large as
     *   the largest read that thread performed, so reading from N threads costs N such buffers
     *   rather than one. Wrapping the array is a small allocation per chunk, dwarfed by the copy
     *   itself.
     * - Reads on a channel are interruptible: if a thread is interrupted while reading, the channel
     *   is closed and the read throws ClosedByInterruptException, which leaves the whole source
     *   unusable rather than just failing that one read. Reading after [close] now throws
     *   ClosedChannelException instead of IOException("Stream Closed").
     */
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
        idleScratchArrays.offer(scratchArray)
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
      private val MAX_IDLE_SCRATCH_ARRAYS = Runtime.getRuntime().availableProcessors()
    }
  }
}
