package leakcanary

sealed class RetainInstanceChange {
  data class CountChanged(val retainedCount: Int) : RetainInstanceChange()
  object Reset : RetainInstanceChange()
}

/**
 * Listener set in [LeakCanary.Config] and called by LeakCanary on a background thread when
 * the number of retained instances changes.
 *
 */
interface OnRetainInstanceListener {

  /**
   * Called when there's a change to the Retained Instances. See [RetainInstanceChange] for
   * possible change events.
   */
  fun onChange(change: RetainInstanceChange)
}