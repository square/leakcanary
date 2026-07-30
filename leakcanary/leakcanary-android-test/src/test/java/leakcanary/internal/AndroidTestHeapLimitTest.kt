package leakcanary.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class AndroidTestHeapLimitTest {

  @Test fun `largeHeap missing points at the manifest of the app under test`() {
    val (heapLimitDetail, raiseHeapLimitOption) = heapLimit(largeHeapEnabled = false)

    assertThat(heapLimitDetail).isEmpty()
    assertThat(raiseHeapLimitOption).contains(
      "Raise that limit to 512 MB by setting android:largeHeap=\"true\" in the manifest of the app " +
        "under test (com.example.app.debug). Setting it in src/androidTest/AndroidManifest.xml has " +
        "no effect"
    )
  }

  @Test fun `largeHeap set on the test apk only is called out as having no effect`() {
    val (heapLimitDetail, raiseHeapLimitOption) = heapLimit(
      largeHeapEnabled = false,
      largeHeapEnabledOnTestApkOnly = true
    )

    assertThat(heapLimitDetail).isEmpty()
    assertThat(raiseHeapLimitOption).contains(
      "Raise that limit to 512 MB by moving android:largeHeap=\"true\" to the manifest of the app " +
        "under test (com.example.app.debug): it is set in the manifest of the test apk, where it " +
        "has no effect"
    )
  }

  @Test fun `largeHeap already set is stated instead of offered`() {
    val (heapLimitDetail, raiseHeapLimitOption) = heapLimit(largeHeapEnabled = true)

    assertThat(heapLimitDetail).isEqualTo(
      ", with android:largeHeap=\"true\" already set in the manifest of the app under test " +
        "(com.example.app.debug)"
    )
    assertThat(raiseHeapLimitOption).isNull()
  }

  private fun heapLimit(
    largeHeapEnabled: Boolean,
    largeHeapEnabledOnTestApkOnly: Boolean = false
  ) = androidTestHeapLimit(
    appUnderTestPackageName = "com.example.app.debug",
    largeHeapEnabled = largeHeapEnabled,
    largeHeapEnabledOnTestApkOnly = largeHeapEnabledOnTestApkOnly,
    largeHeapMaxMemoryMb = 512
  )
}
