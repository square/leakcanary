package leakcanary

import shark.HeapAnalysis

/**
 * Listener set in [LeakCanary.Config] and called by LeakCanary on a background thread when the
 * heap analysis is complete.
 *
 * This is a functional interface with which you can create a [OnHeapAnalyzedListener] from a lambda.
 *
 * Usage:
 *
 * ```kotlin
 * val listener = OnHeapAnalyzedListener { heapAnalysis ->
 *   process(heapAnalysis)
 * }
 * ```
 */
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
      object : OnHeapAnalyzedListener {
        override fun onHeapAnalyzed(heapAnalysis: HeapAnalysis) {
          block(heapAnalysis)
        }
      }
  }
}