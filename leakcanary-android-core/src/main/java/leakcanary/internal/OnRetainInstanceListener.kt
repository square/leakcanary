package leakcanary.internal

internal sealed class RetainInstanceChange {
  data class CountChanged(val retainedCount: Int) : RetainInstanceChange()
  object Reset : RetainInstanceChange()
}

/**
 * Called by LeakCanary when the number of retained instances updates .
 */
internal interface OnRetainInstanceListener {

  /**
   * Called when there's a change to the Retained Instances. See [RetainInstanceChange] for
   * possible change events.
   */
  fun onChange(change: RetainInstanceChange)
}

internal class DefaultOnRetainInstanceListener : OnRetainInstanceListener {

  override fun onChange(change: RetainInstanceChange) {}
}