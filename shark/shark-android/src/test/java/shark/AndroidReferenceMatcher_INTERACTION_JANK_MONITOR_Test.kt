package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder

class AndroidReferenceMatcher_INTERACTION_JANK_MONITOR_Test {

  @Test fun `view-based FrameTracker on API 35+ is a known library leak`() {
    val hprofBytes = dumpFrameTrackerLeak(sdkInt = 35, includeConfig = true, includeViewAndViewRoot = true)

    val analysis = hprofBytes.checkForLeaks<HeapAnalysisSuccess>(
      referenceMatchers = AndroidReferenceMatchers.appDefaults
    )

    assertThat(analysis.applicationLeaks)
      .describedAs(analysis.applicationLeaks.toString())
      .isEmpty()
    assertThat(analysis.libraryLeaks).hasSize(1)
  }

  @Test fun `surface-only FrameTracker on API 37 is a known library leak`() {
    val hprofBytes = dumpFrameTrackerLeak(sdkInt = 37, includeConfig = true, includeViewAndViewRoot = false)

    val analysis = hprofBytes.checkForLeaks<HeapAnalysisSuccess>(
      referenceMatchers = AndroidReferenceMatchers.appDefaults
    )

    assertThat(analysis.applicationLeaks)
      .describedAs(analysis.applicationLeaks.toString())
      .isEmpty()
    assertThat(analysis.libraryLeaks).hasSize(1)
  }

  @Test fun `FrameTracker mViewRoot on API 33 is a known library leak`() {
    val hprofBytes = dumpFrameTrackerLeak(sdkInt = 33, includeConfig = false, includeViewAndViewRoot = true)

    val analysis = hprofBytes.checkForLeaks<HeapAnalysisSuccess>(
      referenceMatchers = AndroidReferenceMatchers.appDefaults
    )

    assertThat(analysis.applicationLeaks)
      .describedAs(analysis.applicationLeaks.toString())
      .isEmpty()
    assertThat(analysis.libraryLeaks).hasSize(1)
  }

  @Test fun `FrameTracker on API 32 is not matched as a library leak`() {
    val hprofBytes = dumpFrameTrackerLeak(sdkInt = 32, includeConfig = false, includeViewAndViewRoot = true)

    val analysis = hprofBytes.checkForLeaks<HeapAnalysisSuccess>(
      referenceMatchers = AndroidReferenceMatchers.appDefaults
    )

    assertThat(analysis.libraryLeaks).isEmpty()
    assertThat(analysis.applicationLeaks).hasSize(1)
  }

  private fun dumpFrameTrackerLeak(
    sdkInt: Int,
    includeConfig: Boolean,
    includeViewAndViewRoot: Boolean
  ): ByteArray = dumpToBytes {
    clazz(
      "android.os.Build",
      staticFields = listOf("MANUFACTURER" to string("Google"), "ID" to string("someId"))
    )
    clazz(
      "android.os.Build\$VERSION",
      staticFields = listOf("SDK_INT" to IntHolder(sdkInt))
    )

    val activity = instance(clazz("androidx.activity.ComponentActivity"))
    keyedWeakReference(activity)

    val view = if (includeViewAndViewRoot) {
      instance(clazz("android.view.View", fields = listOf("mContext" to ReferenceHolder::class)), listOf(activity))
    } else {
      ReferenceHolder(0)
    }

    val viewRootWrapper = if (includeViewAndViewRoot) {
      val viewRootImpl = instance(
        clazz("android.view.ViewRootImpl", fields = listOf("mContext" to ReferenceHolder::class)),
        listOf(activity)
      )
      instance(
        clazz(
          "com.android.internal.jank.FrameTracker\$ViewRootWrapper",
          fields = listOf("mViewRoot" to ReferenceHolder::class)
        ),
        listOf(viewRootImpl)
      )
    } else {
      ReferenceHolder(0)
    }

    val config = if (includeConfig) {
      instance(
        clazz(
          "com.android.internal.jank.InteractionJankMonitor\$Configuration",
          fields = listOf(
            "mContext" to ReferenceHolder::class,
            "mView" to ReferenceHolder::class
          )
        ),
        listOf(activity, view)
      )
    } else {
      ReferenceHolder(0)
    }

    val frameTracker = instance(
      clazz(
        "com.android.internal.jank.FrameTracker",
        fields = listOf(
          "mConfig" to ReferenceHolder::class,
          "mViewRoot" to ReferenceHolder::class
        )
      ),
      listOf(config, viewRootWrapper)
    )

    clazz(
      "android.os.MessageQueue",
      staticFields = listOf("mTracker" to frameTracker)
    )
  }
}
