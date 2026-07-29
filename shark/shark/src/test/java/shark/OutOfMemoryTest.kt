package shark

import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class OutOfMemoryTest {

  @Test fun `out of memory is found in the cause chain`() {
    val outOfMemory = OutOfMemoryError("Java heap space")
    // The shape Shark's hash maps throw, which is how the analysis usually runs out of memory.
    val failure = IllegalStateException(
      "Analysis failed",
      RuntimeException("Not enough memory to allocate buffers", outOfMemory)
    )

    assertThat(failure.outOfMemoryOrNull()).isSameAs(outOfMemory)
  }

  @Test fun `out of memory is found in the throwable itself`() {
    val outOfMemory = OutOfMemoryError("Java heap space")

    assertThat(outOfMemory.outOfMemoryOrNull()).isSameAs(outOfMemory)
  }

  @Test fun `a failure that has nothing to do with memory has no out of memory`() {
    val failure = IllegalStateException("Analysis failed", IOException("Heap dump file deleted"))

    assertThat(failure.outOfMemoryOrNull()).isNull()
  }
}
