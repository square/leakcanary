package leakcanary

/**
 * Listener set in [LeakCanary.Config] and called by LeakCanary on a background thread when
 * the number of retained instances changes.
 *
 */
interface OnRetainInstanceListener {

  /**
   * Called when count of retained instances changes.
   * Use [LeakCanary.Config.retainedVisibleThreshold] to get the threshold count at which
   * a heap dump will get triggered.
   */
  fun onCountChanged(retainedCount: Int)

  /**
   * Called after heap dump is complete to reset retained instances count.
   * This method allows to differentiate from calls to [onCountChanged] with parameter of `0`
   * which can happen when retained instance gets garbage collected and therefore is not leaking.
   */
  fun onReset()
}