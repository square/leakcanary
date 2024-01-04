package leakcanary

/**
 * Tracks deletion of target objects, marking them retained on trigger.
 */
interface TriggeredDeletableObjectReporter {

  /**
   * Start tracking the provided [target] object, with the expectation that it should be eligible
   * for automatic garbage collection soon, i.e. that it should not be strongly reachable by the
   * time [RetainTrigger.markRetainedIfStronglyReachable] is called on the returned
   * [RetainTrigger].
   *
   * If [target] stays strongly reachable, it will be
   * considered "retained".
   *
   * @param target See [DeletableObjectReporter.expectDeletionFor]
   *
   * @param reason See [DeletableObjectReporter.expectDeletionFor]
   */
  fun expectDeletionOnTriggerFor(
    target: Any,
    reason: String
  ): RetainTrigger

  interface RetainTrigger : TrackedObjectReachability {
    /**
     * Marks the tracked object as "retained" if it is still strongly reachable.
     */
    fun markRetainedIfStronglyReachable()
  }
}
