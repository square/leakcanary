package leakcanary

import kotlin.time.Duration

class DefaultDelayDeletableObjectReporter(
  /**
   * A significant enough delay for the GC to get a chance to run and update reachability status.
   */
  private val defaultDelay: Duration,
  private val delayedReporter: DelayedDeletableObjectReporter
) : DeletableObjectReporter {

  override fun expectDeletionFor(
    target: Any,
    reason: String
  ): TrackedObjectReachability {
    return delayedReporter.expectDelayedDeletionFor(target, reason, defaultDelay)
  }
}
