package leakcanary

interface TrackedObjectReachability {
  /**
   * true if the tracked object is currently strongly reachable.
   */
  val isStronglyReachable: Boolean

  /**
   * Whether this object is eligible for automatic garbage collection.
   */
  val isDeletable: Boolean
    get() = !isStronglyReachable

  /**
   * true if the track object has been marked as retained and is currently strongly reachable.
   */
  val isRetained: Boolean
}
