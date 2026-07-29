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

  @Test fun `largeHeap missing points at the manifest of the app under test`() {
    val guidance = guidance(
      AndroidInstrumentationTest(
        appUnderTestPackageName = "com.example.app.debug",
        largeHeapEnabled = false,
        largeHeapEnabledOnTestApkOnly = false,
        largeHeapMaxMemoryMb = 512
      )
    )

    assertThat(guidance).contains(
      "Raise that limit to 512 MB by setting android:largeHeap=\"true\" in the manifest of the app " +
        "under test (com.example.app.debug). Setting it in src/androidTest/AndroidManifest.xml has " +
        "no effect"
    )
  }

  @Test fun `largeHeap set on the test apk only is called out as having no effect`() {
    val guidance = guidance(
      AndroidInstrumentationTest(
        appUnderTestPackageName = "com.example.app.debug",
        largeHeapEnabled = false,
        largeHeapEnabledOnTestApkOnly = true,
        largeHeapMaxMemoryMb = 512
      )
    )

    assertThat(guidance).contains(
      "Raise that limit to 512 MB by moving android:largeHeap=\"true\" to the manifest of the app " +
        "under test (com.example.app.debug): it is set in the manifest of the test apk, where it " +
        "has no effect"
    )
  }

  @Test fun `largeHeap already set is stated instead of offered`() {
    val guidance = guidance(
      AndroidInstrumentationTest(
        appUnderTestPackageName = "com.example.app.debug",
        largeHeapEnabled = true,
        largeHeapEnabledOnTestApkOnly = false,
        largeHeapMaxMemoryMb = 512
      ),
      maxMemoryMb = 512
    )

    assertThat(guidance).startsWith(
      "Not enough memory to detect heap growth: this process can use up to 512 MB, with " +
        "android:largeHeap=\"true\" already set in the manifest of the app under test " +
        "(com.example.app.debug). You can:"
    )
    assertThat(guidance).doesNotContain("Raise that limit")
  }

  @Test fun `an Android process we know nothing about still gets largeHeap guidance`() {
    val guidance = guidance(AndroidApp)

    assertThat(guidance).contains(
      "Increase the memory available to the app with android:largeHeap=\"true\""
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
