package leakcanary

import kotlin.time.Duration.Companion.milliseconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ReferenceQueueRetainedObjectTrackerTest {

  private val onObjectRetainedListener: OnObjectRetainedListener = OnObjectRetainedListener {
    objectRetainedListenerInvoked = true
  }

  private val referenceQueueReachabilityWatcher =
    ReferenceQueueRetainedObjectTracker({ time.milliseconds }, onObjectRetainedListener)
  var time: Long = 0

  var ref: Any? = Any()

  var objectRetainedListenerInvoked = false

  @Test fun `unreachable object not retained`() {
    val retainTrigger =
      referenceQueueReachabilityWatcher.expectDeletionOnTriggerFor(
        ref!!, "unreachable object not retained"
      )
    ref = null
    GcTrigger.inProcess().runGc()
    retainTrigger.markRetainedIfStronglyReachable()
    assertThat(referenceQueueReachabilityWatcher.hasRetainedObjects).isFalse()
    assertThat(objectRetainedListenerInvoked).isFalse()
  }

  @Test fun `reachable object retained`() {
    val retainTrigger =
      referenceQueueReachabilityWatcher.expectDeletionOnTriggerFor(
        ref!!, "reachable object retained"
      )
    GcTrigger.inProcess().runGc()
    retainTrigger.markRetainedIfStronglyReachable()
    assertThat(referenceQueueReachabilityWatcher.hasRetainedObjects).isTrue()
    assertThat(objectRetainedListenerInvoked).isTrue()
  }
}
