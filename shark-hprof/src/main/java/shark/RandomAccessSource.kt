package shark

import okio.Buffer
import java.io.Closeable
import java.io.IOException

interface RandomAccessSource : Closeable {
  @Throws(IOException::class)
  fun read(
    sink: Buffer,
    position: Long,
    byteCount: Long
  ): Long
}