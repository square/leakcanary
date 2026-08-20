package shark

import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class CopyingSourceTest {

  @Test fun `repeating pattern is cut short to land on the byte count`() {
    val source = Buffer().apply { write(ByteArray(5) { 1 }) }
    val sink = Buffer()
    val copyingSource = CopyingSource(source, sink)

    copyingSource.overwriteRepeating(byteCount = 5, pattern = byteArrayOf(2, 3))

    assertThat(sink.readByteArray()).isEqualTo(byteArrayOf(2, 3, 2, 3, 2))
    assertThat(source.size).isEqualTo(0)
  }

  /**
   * A primitive array holding more than 2 GB of elements can't be covered by a heap dump built with
   * the `dump {}` DSL: writing one means holding its elements in memory first, which is 2 GB of heap
   * for the fixture alone, on top of a 2 GB file. So this overwrites that many bytes of a source
   * that hands out an endless run of zeros, into a sink that counts what it's given, which costs
   * nothing.
   */
  @Test fun `overwrites more than 2 GB worth of bytes`() {
    // One byte past what an Int holds.
    val byteCount = Int.MAX_VALUE.toLong() + 1
    val countingSink = CountingSink()
    val bufferedSink = countingSink.buffer()
    val copyingSource = CopyingSource(EndlessZerosSource().buffer(), bufferedSink)

    copyingSource.overwriteRepeating(byteCount, ByteArray(8192) { 63 })
    bufferedSink.flush()

    assertThat(copyingSource.bytesRead).isEqualTo(byteCount)
    assertThat(countingSink.byteCount).isEqualTo(byteCount)
  }

  /** Hands out an endless run of zeros, all of them from one backing array. */
  private class EndlessZerosSource : Source {

    private val zeros = Buffer().apply { write(ByteArray(CHUNK_BYTE_SIZE)) }

    override fun read(
      sink: Buffer,
      byteCount: Long
    ): Long {
      val readByteCount = minOf(byteCount, zeros.size)
      zeros.copyTo(sink, 0, readByteCount)
      return readByteCount
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = Unit
  }

  /** Counts what it's given and throws it away, so that writing gigabytes costs nothing. */
  private class CountingSink : Sink {

    var byteCount = 0L
      private set

    override fun write(
      source: Buffer,
      byteCount: Long
    ) {
      source.skip(byteCount)
      this.byteCount += byteCount
    }

    override fun flush() = Unit

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = Unit
  }

  companion object {
    private const val CHUNK_BYTE_SIZE = 8192
  }
}
