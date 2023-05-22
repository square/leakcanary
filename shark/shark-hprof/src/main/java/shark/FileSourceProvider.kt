package shark

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min
import okio.Buffer
import okio.BufferedSource
import okio.Okio

class FileSourceProvider(private val file: File) : DualSourceProvider {
  override fun openStreamingSource(): BufferedSource = Okio.buffer(Okio.source(file.inputStream()))

  override fun openRandomAccessSource(): RandomAccessSource {

    val randomAccessFile = RandomAccessFile(file, "r")

    val arrayBuffer = ByteArray(500_000)

    return object : RandomAccessSource {
      override fun read(
        sink: Buffer,
        position: Long,
        byteCount: Long
      ): Long {
        val byteCountInt = byteCount.toInt()
        randomAccessFile.seek(position)
        var totalBytesRead = 0
        val maxRead = arrayBuffer.size
        while (totalBytesRead < byteCount) {
          val toRead = min(byteCountInt - totalBytesRead, maxRead)
          val bytesRead = randomAccessFile.read(arrayBuffer, 0, toRead)
          if (bytesRead == -1) {
            check(totalBytesRead != 0) {
              "Did not expect to reach end of file after reading 0 bytes"
            }
            break
          }
          sink.write(arrayBuffer, 0, bytesRead)
          totalBytesRead += bytesRead
        }
        return totalBytesRead.toLong()
      }

      override fun close() {
        try {
          randomAccessFile.close()
        } catch (ignored: Throwable) {
          SharkLog.d(ignored) { "Failed to close file, ignoring" }
        }
      }
    }
  }
}

