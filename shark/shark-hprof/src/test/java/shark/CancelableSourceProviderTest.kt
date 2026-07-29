package shark

import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class CancelableSourceProviderTest {

  private var cancelReason: String? = null

  private val sourceProvider =
    CancelableSourceProvider(ByteArraySourceProvider(CONTENT), CancelSignal { cancelReason })

  @Test fun `streaming source reads the content when not canceled`() {
    val source = sourceProvider.openStreamingSource()

    assertThat(source.readByteArray()).isEqualTo(CONTENT)
  }

  @Test fun `streaming source read throws once canceled`() {
    val source = sourceProvider.openStreamingSource()

    cancelReason = CANCEL_REASON

    assertThatThrownBy { source.readByteArray() }
      .isInstanceOf(CanceledException::class.java)
      .hasMessage(CANCEL_REASON)
  }

  @Test fun `streaming source reads what was already buffered, then throws`() {
    val source = sourceProvider.openStreamingSource()
    val firstByte = source.readByte()

    cancelReason = CANCEL_REASON

    assertThat(firstByte).isEqualTo(CONTENT[0])
    assertThatThrownBy { source.readByteArray() }
      .isInstanceOf(CanceledException::class.java)
  }

  @Test fun `random access source reads the content when not canceled`() {
    sourceProvider.openRandomAccessSource().use { source ->
      val sink = Buffer()

      assertThat(source.read(sink, 0, CONTENT.size.toLong())).isEqualTo(CONTENT.size.toLong())
      assertThat(sink.readByteArray()).isEqualTo(CONTENT)
    }
  }

  @Test fun `random access source read throws once canceled`() {
    sourceProvider.openRandomAccessSource().use { source ->
      val sink = Buffer()
      source.read(sink, 0, 4)

      cancelReason = CANCEL_REASON

      assertThatThrownBy { source.read(sink, 4, 4) }
        .isInstanceOf(CanceledException::class.java)
        .hasMessage(CANCEL_REASON)
    }
  }

  /**
   * [RandomAccessSource.asStreamingSource] is a default implementation that reads through
   * [RandomAccessSource.read], so it only checks if the wrapper implements that method rather than
   * delegating it.
   */
  @Test fun `streaming view of a random access source throws once canceled`() {
    sourceProvider.openRandomAccessSource().use { source ->
      val streamingSource = source.asStreamingSource()

      cancelReason = CANCEL_REASON

      assertThatThrownBy { streamingSource.readByteArray() }
        .isInstanceOf(CanceledException::class.java)
        .hasMessage(CANCEL_REASON)
    }
  }

  @Test fun `a source provider that is never canceled reads the content`() {
    val neverCanceled =
      CancelableSourceProvider(ByteArraySourceProvider(CONTENT), CancelSignal.NEVER)

    assertThat(neverCanceled.openStreamingSource().readByteArray()).isEqualTo(CONTENT)
  }

  companion object {
    private val CONTENT = ByteArray(64) { it.toByte() }
    private const val CANCEL_REASON = "canceled by a test"
  }
}
