package leakcanary

import kotlin.time.Duration

/**
 * Helper util for tracking retained objects.
 *
 * A retained object is an object that is expected to be deleted but stays strongly reachable,
 * preventing it from being garbage collected.
 *
 * - A target object is "tracked" after being reported to an associated [DeletableObjectReporter] or
 * [TriggeredDeletableObjectReporter].
 * - If at any point in time that target object becomes weakly reachable, then
 * [RetainedObjectTracker] will stop tracking that object.
 */
interface RetainedObjectTracker {

  /**
   * Returns true if any of the tracked objects are currently retained.
   */
  val hasRetainedObjects: Boolean

  /**
   * Returns the number of retained objects.
   */
  val retainedObjectCount: Int

  /**
   * Returns true if there are any tracked objects that aren't currently weakly reachable.
   */
  val hasTrackedObjects: Boolean

  /**
   * Returns the number of tracked objects that aren't weakly
   * reachable.
   */
  val trackedObjectCount: Int

  /**
   * Clears weak reachability expectations for objects that were created before [uptime].
   *
   * @param uptime A time in the past from [UptimeClock.uptime].
   */
 fun clearObjectsTrackedBefore(uptime: Duration)

  /**
   * Clears weak reachability expectations for all tracked objects.
   */
  fun clearAllObjectsTracked()
}
