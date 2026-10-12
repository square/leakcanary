package leakcanary

import android.os.SystemClock
import kotlin.time.Duration.Companion.milliseconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class FinalizingInProcessGcTriggerTest {

  @Test fun object_collected_by_the_last_gc_is_not_retained() {
    val tracker = ReferenceQueueRetainedObjectTracker({ SystemClock.uptimeMillis().milliseconds }) {}
    watchObjectOnlyTheLastGcCanCollect(tracker)

    FinalizingInProcessGcTrigger.runGc()

    assertThat(tracker.retainedObjectCount).isEqualTo(0)
  }

  /**
   * Kept in its own function so that no local in the test's frame keeps either object alive.
   */
  private fun watchObjectOnlyTheLastGcCanCollect(tracker: ReferenceQueueRetainedObjectTracker) {
    val target = Any()
    // Strongly reachable until the first GC finds ReleaseOnFinalize unreachable and
    // runFinalization() finalizes it, so it's the last GC that collects target.
    heldUntilFinalized = target
    ReleaseOnFinalize()
    tracker.expectDeletionOnTriggerFor(target, "test").markRetainedIfStronglyReachable()
  }

  private class ReleaseOnFinalize {
    @Suppress("ProtectedInFinal", "unused")
    protected fun finalize() {
      heldUntilFinalized = null
    }
  }

  companion object {
    @Volatile
    private var heldUntilFinalized: Any? = null
  }
}
