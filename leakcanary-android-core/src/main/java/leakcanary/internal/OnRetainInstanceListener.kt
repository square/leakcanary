package leakcanary.internal

internal sealed class RetainInstanceEvent {
  object NoMoreObjects : RetainInstanceEvent()
  sealed class CountChanged : RetainInstanceEvent() {
    class BelowThreshold(val retainedCount: Int) : RetainInstanceEvent()
    class DumpingDisabled(val reason: String) : RetainInstanceEvent()
    object DumpHappenedRecently : RetainInstanceEvent()
  }
}

/**
 * Called by LeakCanary when the number of retained instances updates .
 */
internal interface OnRetainInstanceListener {

  /**
   * Called when there's a change to the Retained Instances. See [RetainInstanceEvent] for
   * possible events.
   */
  fun onEvent(event: RetainInstanceEvent)
}

internal class DefaultOnRetainInstanceListener : OnRetainInstanceListener {

  override fun onEvent(event: RetainInstanceEvent) {}
}