package leakcanary.internal

import java.io.File
import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure

class OutOfMemoryGuidanceTest {

  @Test fun `out of memory wrapped by Shark is recognized`() {
    // The shape Shark's hash maps throw, which is how the analysis usually runs out of memory.
    val failure = failure(
      RuntimeException(
        "Not enough memory to allocate buffers for rehashing: 1 -> 4194304",
        OutOfMemoryError("Java heap space")
      )
    )

    val withGuidance = failure.withOutOfMemoryGuidance()

    assertThat(withGuidance.exception.cause!!.message)
      .startsWith("Not enough memory to analyze heap. You can:")
  }

  @Test fun `out of memory as the direct cause is recognized`() {
    val failure = failure(OutOfMemoryError("Java heap space"))

    val withGuidance = failure.withOutOfMemoryGuidance()

    assertThat(withGuidance.exception.cause!!.message)
      .startsWith("Not enough memory to analyze heap. You can:")
  }

  @Test fun `failure that has nothing to do with memory is left alone`() {
    val failure = failure(IOException("Heap dump file deleted"))

    val withGuidance = failure.withOutOfMemoryGuidance()

    assertThat(withGuidance).isSameAs(failure)
  }

  @Test fun `original failure is kept as the cause of the guidance`() {
    val outOfMemory = OutOfMemoryError("Java heap space")
    val sharkFailure = RuntimeException("Not enough memory to allocate buffers", outOfMemory)

    val withGuidance = failure(sharkFailure).withOutOfMemoryGuidance()

    assertThat(withGuidance.exception.cause!!.cause).isSameAs(sharkFailure)
  }

  @Test fun `failure that ran out of memory is out of memory`() {
    val failure = failure(
      RuntimeException(
        "Not enough memory to allocate buffers for rehashing: 1 -> 4194304",
        OutOfMemoryError("Java heap space")
      )
    )

    assertThat(failure.isOutOfMemory).isTrue()
  }

  @Test fun `failure that has nothing to do with memory is not out of memory`() {
    val failure = failure(IOException("Heap dump file deleted"))

    assertThat(failure.isOutOfMemory).isFalse()
  }

  @Test fun `failure is still out of memory once the guidance replaced the exception`() {
    // The failure screen reads isOutOfMemory off the failure that was stored, which by then has
    // been through withOutOfMemoryGuidance. It has to still know not to ask for a bug report.
    val failure = failure(
      RuntimeException(
        "Not enough memory to allocate buffers for rehashing: 1 -> 4194304",
        OutOfMemoryError("Java heap space")
      )
    )

    val withGuidance = failure.withOutOfMemoryGuidance()

    assertThat(withGuidance.isOutOfMemory).isTrue()
  }

  private fun failure(cause: Throwable) = HeapAnalysisFailure(
    heapDumpFile = File("heap.hprof"),
    createdAtTimeMillis = 0,
    analysisDurationMillis = 0,
    exception = HeapAnalysisException(cause)
  )
}
