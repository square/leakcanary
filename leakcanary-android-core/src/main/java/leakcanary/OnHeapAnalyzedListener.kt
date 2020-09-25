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
}