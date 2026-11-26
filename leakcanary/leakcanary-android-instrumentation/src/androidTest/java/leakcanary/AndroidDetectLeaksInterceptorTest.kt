package leakcanary

import android.app.Application
import android.os.Build.VERSION.SDK_INT
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import leakcanary.HeapAnalysisDecision.NoHeapAnalysis
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class AndroidDetectLeaksInterceptorTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Before fun setUp() {
    AppWatcher.objectWatcher.clearAllObjectsTracked()
  }

  @After fun tearDown() {
    AppWatcher.objectWatcher.clearAllObjectsTracked()
  }

  @Test fun viewLeftInViewLocationHolderPoolIsNotRetained() {
    assumeTrue("ViewLocationHolder.mRoot only leaks on Android 9", SDK_INT == 28)
    val application = instrumentation.targetContext.applicationContext as Application
    instrumentation.runOnMainSync {
      val viewGroup = FrameLayout(application)
      viewGroup.addView(View(application))
      // Recycles a ViewLocationHolder that keeps viewGroup as its mRoot.
      viewGroup.addChildrenForAccessibility(ArrayList())
      AppWatcher.objectWatcher.expectWeaklyReachable(viewGroup, "Detached from its parent")
    }

    assertNoHeapAnalysisAfterFirstGc()
  }

  @Test fun textLeftInAccessibilityNodeInfoPoolIsNotRetained() {
    assumeTrue("AccessibilityNodeInfo.mOriginalText only leaks before Android 9", SDK_INT < 28)
    recycleNodeInfoWithWatchedText()

    assertNoHeapAnalysisAfterFirstGc()
  }

  // A function of its own, so that no local of the test method references the text.
  private fun recycleNodeInfoWithWatchedText() {
    val text = StringBuilder("Detached TextView text")
    val nodeInfo = AccessibilityNodeInfo.obtain()
    nodeInfo.text = text
    // Puts nodeInfo back in the pool, still holding text as its mOriginalText.
    @Suppress("DEPRECATION")
    nodeInfo.recycle()
    AppWatcher.objectWatcher.expectWeaklyReachable(text, "Its node info was recycled")
  }

  private fun assertNoHeapAnalysisAfterFirstGc() {
    val startUptimeMillis = SystemClock.uptimeMillis()
    val decision = AndroidDetectLeaksInterceptor().waitUntilReadyForHeapAnalysis()
    val durationMillis = SystemClock.uptimeMillis() - startUptimeMillis

    assertThat(decision).isInstanceOf(NoHeapAnalysis::class.java)
    // When its first GC leaves watched objects, the interceptor sleeps 4 seconds before the next.
    assertThat(durationMillis).isLessThan(4000)
  }
}
