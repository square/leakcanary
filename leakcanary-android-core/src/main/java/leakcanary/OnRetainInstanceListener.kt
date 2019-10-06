package leakcanary

import leakcanary.OnHeapAnalyzedListener.Companion.invoke

/**
 * Listener set in [LeakCanary.Config] and called by LeakCanary on a background thread when the
 * heap analysis is complete.
 *
 * You can create a [OnRetainInstanceListener] from a lambda by calling [invoke].
 */
interface OnRetainInstanceListener {

  fun onCountChanged(retainedCount: Int)

  companion object {
    /**
     * Utility function to create a [OnRetainInstanceListener] from the passed in [block] lambda
     * instead of using the anonymous `object : OnHeapAnalyzedListener` syntax.
     *
     * Usage:
     *
     * ```kotlin
     * val listener = OnHeapAnalyzedListener {
     *
     * }
     * ```
     */
    inline operator fun invoke(crossinline block: (Int) -> Unit): OnRetainInstanceListener =
      object : OnRetainInstanceListener {
        override fun onCountChanged(retainedCount: Int) {
          block(retainedCount)
        }
      }
  }
}