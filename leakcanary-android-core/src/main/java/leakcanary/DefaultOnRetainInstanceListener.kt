package leakcanary

/**
 * Default implementation of [OnRetainInstanceListener] that doesn't do anything
 */
class DefaultOnRetainInstanceListener : OnRetainInstanceListener {

  override fun onCountChanged(retainedCount: Int) {}

  override fun onReset() {}
}