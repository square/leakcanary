package leakcanary.internal

import com.android.tools.perflib.captures.DataBuffer
import sun.nio.ch.DirectBuffer
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel.MapMode

// THIS IS UNUSED AND DOES NOT WORK AT THE MOMENT
// TODO Try again when we have a working parser.
// Goal is to build a seekable okio source on top of memory mapped byte buffers
class MemoryMappedFileInputStream(
  file: File,
  private val bufferSize: Int,
  private val padding: Int
) : InputStream() {

  var position = 0

  private val length = file.length()

  private val byteBuffers: Array<ByteBuffer>

  private val index
    get() = position / bufferSize

  private val offset
    get() = position % bufferSize

  init {
    val buffersCount = (length / bufferSize).toInt() + 1
    val byteBuffers = arrayOfNulls<ByteBuffer>(buffersCount)

    FileInputStream(file)
        .use { inputStream ->
          var offset: Long = 0
          for (i in 0 until buffersCount) {
            val size = Math.min(length - offset, (bufferSize + padding).toLong())

            byteBuffers[i] = inputStream.channel
                .map(MapMode.READ_ONLY, offset, size)
                .order(DataBuffer.HPROF_BYTE_ORDER)
            offset += bufferSize.toLong()
          }
        }

    @Suppress("UNCHECKED_CAST")
    this.byteBuffers = byteBuffers as Array<ByteBuffer>
  }

  override fun close() {
    try {
      for (i in byteBuffers.indices) {
        (byteBuffers[i] as DirectBuffer).cleaner()
            .clean()
      }
    } catch (ex: Exception) {
      ex.printStackTrace()
      TODO("Check this works on Android")
      // ignore, this is a best effort attempt.
    }
  }

  override fun read(): Int {
    TODO("not supported")
  }

  override fun read(b: ByteArray): Int {
    TODO("not supported")
  }

  override fun read(
    buffer: ByteArray,
    bufferOffset: Int,
    length: Int
  ): Int {
    println("Read $length bytes with offset $bufferOffset")
    if (length == 0) return 0
    require(length > 0) { "length < 0: $length" }
    require(
        length < DEFAULT_MEMORY_MAPPED_BUFFER_SIZE
    ) { "$length > $DEFAULT_MEMORY_MAPPED_BUFFER_SIZE" }
    require(buffer.size - bufferOffset >= length) { "${buffer.size} - $bufferOffset < $length" }
    byteBuffers[index].position(offset)
    val bytesRead: Int
    if (length <= byteBuffers[index].remaining()) {
      byteBuffers[index].get(buffer, bufferOffset, length)
      bytesRead = length
    } else {
      if (index == byteBuffers.lastIndex) {
        bytesRead = byteBuffers[index].remaining()
        byteBuffers[index].get(buffer, bufferOffset, bytesRead)
      } else {
        // Wrapped read
        val split = bufferSize - byteBuffers[index].position()
        byteBuffers[index].get(buffer, bufferOffset, split)
        byteBuffers[index + 1].position(0)

        val readInNextBuffer = length - split

        val remainingInNextBuffer = byteBuffers[index + 1].remaining()
        if (remainingInNextBuffer < readInNextBuffer) {
          bytesRead = split + remainingInNextBuffer
          byteBuffers[index + 1].get(buffer, bufferOffset + split, remainingInNextBuffer)
        } else {
          byteBuffers[index + 1].get(buffer, bufferOffset + split, readInNextBuffer)
          bytesRead = length
        }
      }
    }
    position += bytesRead

    return bytesRead
  }

  companion object {
    fun File.memoryMappedInputStream(): MemoryMappedFileInputStream =
      MemoryMappedFileInputStream(
          this, DEFAULT_MEMORY_MAPPED_BUFFER_SIZE, DEFAULT_MEMORY_MAPPED_PADDING
      )

    // Default chunk size is 1 << 30, or 1,073,741,824 bytes.
    const val DEFAULT_MEMORY_MAPPED_BUFFER_SIZE = 1 shl 30

    // Eliminate wrapped, multi-byte reads across chunks in most cases.
    const val DEFAULT_MEMORY_MAPPED_PADDING = 1024

  }
}


