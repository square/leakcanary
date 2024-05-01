package leakcanary

@Deprecated("Use DeletableObjectReporter instead", ReplaceWith("DeletableObjectReporter"))
fun interface ReachabilityWatcher {

  /**
   * Expects the provided [watchedObject] to become weakly reachable soon. If not,
   * [watchedObject] will be considered retained.
   */
  fun expectWeaklyReachable(
    watchedObject: Any,
    description: String
  )

  fun asDeletableObjectReporter(): DeletableObjectReporter =
    DeletableObjectReporter { target, reason ->
      expectWeaklyReachable(target, reason)
      // This exists for backward-compatibility purposes and as such is unable to return
      // an accurate [TrackedObjectReachability] implementation.
      object : TrackedObjectReachability {
        override val isStronglyReachable: Boolean
          get() = error("Use a non deprecated DeletableObjectReporter implementation instead")
        override val isRetained: Boolean
          get() = error("Use a non deprecated DeletableObjectReporter implementation instead")
      }
    }
}
