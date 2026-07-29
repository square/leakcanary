package shark

import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSourceProviderTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test fun `random access source reads the content`() {
    fileSourceProvider().openRandomAccessSource().use { source ->
      val sink = Buffer()

      assertThat(source.read(sink, 0, CONTENT.size.toLong())).isEqualTo(CONTENT.size.toLong())
      assertThat(sink.readByteArray()).isEqualTo(CONTENT)
    }
  }

  /**
   * A record can hold more than [Int.MAX_VALUE] bytes, so a read has to be asked for that many. A
   * read that runs past the end of the file reports how much it did read, which is what makes this
   * observable on a file small enough for a test: narrowed to an Int, a byteCount that large wraps
   * negative and the read reports 0 bytes without having read any.
   */
  @Test fun `random access source reads what the file holds when asked for more than 2 GB`() {
    fileSourceProvider().openRandomAccessSource().use { source ->
      val sink = Buffer()

      val bytesRead = source.read(sink, 0, MORE_THAN_INT_MAX_VALUE_BYTES)

      assertThat(bytesRead).isEqualTo(CONTENT.size.toLong())
      assertThat(sink.readByteArray()).isEqualTo(CONTENT)
    }
  }

  private fun fileSourceProvider() =
    FileSourceProvider(temporaryFolder.newFile().apply { writeBytes(CONTENT) })

  companion object {
    private val CONTENT = ByteArray(64) { it.toByte() }
    private const val MORE_THAN_INT_MAX_VALUE_BYTES = Int.MAX_VALUE.toLong() + 1
  }
}
