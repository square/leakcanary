package leakcanary

import shark.HeapAnalysis

/**
 * Deprecated, add to LeakCanary.config.eventListeners instead.
 * Called after [leakcanary.EventListener.Event.HeapAnalysisDone].
 */
@Deprecated(message = "Add to LeakCanary.config.eventListeners instead")
fun interface OnHeapAnalyzedListener {

  /**
   * @see OnHeapAnalyzedListener
   */
  fun onHeapAnalyzed(heapAnalysis: HeapAnalysis)

  companion object {
    /**
     * Utility function to create a [OnHeapAnalyzedListener] from the passed in [block] lambda
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
    inline operator fun invoke(crossinline block: (HeapAnalysis) -> Unit): OnHeapAnalyzedListener =
      OnHeapAnalyzedListener { heapAnalysis -> block(heapAnalysis) }
  }
}
