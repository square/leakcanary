package leakcanary

import kotlin.time.Duration

class DelayedDeletableObjectReporter(
  private val deletableObjectReporter: TriggeredDeletableObjectReporter,
  private val delayedExecutor: DelayedExecutor
) {
  /**
   * Same as [DeletableObjectReporter.expectDeletionFor] but allows providing a delay for when
   * [target] is expected to be deleted.
   *
   * @param delayUptime how long to wait until [target] is considered retained.
   * Should be a significant enough delay for the GC to get a chance to run and update reachability
   * status. You should generally use [DefaultDelayDeletableObjectReporter], this is only useful
   * if [target] is a special object that you know needs more time to stop being strongly reachable.
   *
   */
  fun expectDelayedDeletionFor(
    target: Any,
    reason: String,
    delayUptime: Duration
  ): TrackedObjectReachability {
    val retainTrigger =
      deletableObjectReporter.expectDeletionOnTriggerFor(target, reason)
    delayedExecutor.executeWithDelay(delayUptime) {
      retainTrigger.markRetainedIfStronglyReachable()
    }
    return retainTrigger
  }
}
