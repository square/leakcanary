package leakcanary

import androidx.lifecycle.LifecycleObserver
import leakcanary.TestUtils.detectLeaks
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ObjectInspectorTest : HasActivityTestRule<TestActivity> {

  @get:Rule
  override val activityRule = activityTestRule<TestActivity>(launchActivity = false)

  @Before fun setUp() {
    AppWatcher.objectWatcher.clearAllObjectsTracked()
  }

  @After fun tearDown() {
    AppWatcher.objectWatcher.clearAllObjectsTracked()
  }

  @Test fun LifecycleRegistry_LeakingStatus_Is_Reported() {
    triggersOnActivityCreated {
      activityRule.launchActivity(null)
    }
    activity.lifecycle retained {
      runOnMainSync {
        val observer = object : LifecycleObserver {}
        activity.lifecycle.addObserver(observer)
        AppWatcher.objectWatcher.expectWeaklyReachable(observer, "observer")
      }
      triggersOnActivityDestroyed {
        activityRule.finishActivity()
      }
      Thread.sleep(AppWatcher.retainedDelayMillis)

      val heapAnalysis = detectLeaks()

      val leaktrace = heapAnalysis.allLeaks.single().leakTraces.single()
      val ref = leaktrace.referencePath.single { it.owningClassSimpleName == "LifecycleRegistry" }
      val lifecycleRegistry = ref.originObject
      assertThat(lifecycleRegistry.labels.single()).isEqualTo("state = DESTROYED")
    }
  }
}
