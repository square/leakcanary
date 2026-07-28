package leakcanary

import androidx.lifecycle.LifecycleObserver
import leakcanary.TestUtils.detectLeaks
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ObjectInspectorTest : HasActivityTestRule<TestActivity> {

  class ObserverHoldingLeaky : LifecycleObserver {
    val leaky = Any()
  }

  @get:Rule
  override val activityRule = activityTestRule<TestActivity>(launchActivity = false)

  @Before fun setUp() {
    AppWatcher.objectWatcher.clearAllObjectsTracked()
  }

  @After fun tearDown() {
    AppWatcher.objectWatcher.clearAllObjectsTracked()
  }

  /**
   * A lifecycle observer is held by the [androidx.lifecycle.LifecycleRegistry] it was added to, so
   * that registry shows up in the leak trace of anything the observer holds on to.
   *
   * The registry of a destroyed lifecycle owner would be a better fit, but androidx.lifecycle
   * releases the observers of a registry that reaches the DESTROYED state, so a destroyed registry
   * can no longer be part of a leak trace. `shark.AndroidObjectInspectorsTest` covers that case
   * from a heap dump instead.
   */
  @Test fun LifecycleRegistry_LeakingStatus_Is_Reported() {
    triggersOnActivityCreated {
      activityRule.launchActivity(null)
    }

    runOnMainSync {
      val observer = ObserverHoldingLeaky()
      activity.lifecycle.addObserver(observer)
      AppWatcher.objectWatcher.expectWeaklyReachable(observer.leaky, "leaky leaks")
    }
    Thread.sleep(AppWatcher.retainedDelayMillis)

    val heapAnalysis = detectLeaks()

    val leakTrace = heapAnalysis.applicationLeaks.single().leakTraces.single()
    val lifecycleRegistry = leakTrace.referencePath
      .single { it.owningClassSimpleName == "LifecycleRegistry" }
      .originObject
    assertThat(lifecycleRegistry.leakingStatusReason)
      .describedAs("$heapAnalysis")
      .isEqualTo("state is RESUMED")
  }
}
