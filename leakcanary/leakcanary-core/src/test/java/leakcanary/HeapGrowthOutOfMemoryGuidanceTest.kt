package leakcanary

import java.io.IOException
import leakcanary.HeapLimitSource.AndroidApp
import leakcanary.HeapLimitSource.AndroidInstrumentationTest
import leakcanary.HeapLimitSource.Jvm
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class HeapGrowthOutOfMemoryGuidanceTest {

  @Test fun `out of memory wrapped by Shark is recognized`() {
    // The shape Shark's hash maps throw, which is how the traversal usually runs out of memory.
    val failure = RuntimeException(
      "Not enough memory to allocate buffers for rehashing: 1 -> 4194304",
      OutOfMemoryError("Java heap space")
    )

    val guidance = heapGrowthOutOfMemoryGuidanceOrNull(failure, heapDumpsDeleted = true)

    assertThat(guidance).startsWith("Not enough memory to detect heap growth")
  }

  @Test fun `failure that has nothing to do with memory gets no guidance`() {
    val failure = IOException("Heap dump file deleted")

    val guidance = heapGrowthOutOfMemoryGuidanceOrNull(failure, heapDumpsDeleted = true)

    assertThat(guidance).isNull()
  }

  @Test fun `what an instrumentation test process says about its heap limit is passed on`() {
    val guidance = guidance(
      AndroidInstrumentationTest(
        heapLimitDetail = ", with android:largeHeap=\"true\" already set",
        raiseHeapLimitOption = "Raise that limit to 512 MB."
      )
    )

    assertThat(guidance).startsWith(
      "Not enough memory to detect heap growth: this process can use up to 192 MB, with " +
        "android:largeHeap=\"true\" already set. You can:\n- Raise that limit to 512 MB."
    )
  }

  @Test fun `an instrumentation test process with no way to raise its limit is offered none`() {
    val guidance = guidance(
      AndroidInstrumentationTest(heapLimitDetail = "", raiseHeapLimitOption = null)
    )

    assertThat(guidance).startsWith(
      "Not enough memory to detect heap growth: this process can use up to 192 MB. You can:"
    )
    assertThat(guidance).doesNotContain("Raise that limit")
  }

  @Test fun `an Android process we know nothing about is warned about the test apk manifest`() {
    val guidance = guidance(AndroidApp)

    assertThat(guidance).contains(
      "Increase the memory available to this process with android:largeHeap=\"true\". In an " +
        "instrumentation test that has to be the manifest of the app under test"
    )
  }

  @Test fun `a JVM is pointed at the Xmx flag`() {
    val guidance = guidance(Jvm)

    assertThat(guidance).contains("Raise the memory limit of the JVM running this test with the -Xmx")
  }

  @Test fun `deleted heap dumps are worth keeping`() {
    val guidance = guidance(Jvm, heapDumpsDeleted = true)

    assertThat(guidance).contains(
      "Keep the heap dumps (heapDumpStorageStrategy = HeapDumpStorageStrategy.KeepHeapDumps())"
    )
  }

  @Test fun `kept heap dumps are there to be analyzed`() {
    val guidance = guidance(Jvm, heapDumpsDeleted = false)

    assertThat(guidance).contains("on the heap dumps this run kept")
    assertThat(guidance).doesNotContain("Keep the heap dumps")
  }

  private fun guidance(
    heapLimitSource: HeapLimitSource,
    maxMemoryMb: Long = 192,
    heapDumpsDeleted: Boolean = true
  ) = heapGrowthOutOfMemoryGuidance(
    maxMemoryMb = maxMemoryMb,
    heapLimitSource = heapLimitSource,
    heapDumpsDeleted = heapDumpsDeleted
  )
}
