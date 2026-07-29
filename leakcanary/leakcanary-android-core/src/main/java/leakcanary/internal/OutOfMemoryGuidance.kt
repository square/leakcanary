package leakcanary.internal

import shark.HeapAnalysisException
import shark.HeapAnalysisFailure

/**
 * Whether the analysis failed because it ran out of memory.
 *
 * The whole cause chain is searched rather than just the direct cause, because an
 * [OutOfMemoryError] rarely is the direct cause: Shark's hash maps catch it while allocating their
 * buffers and rethrow it wrapped in a [RuntimeException] that says which buffer they failed to
 * allocate, which is the most common way for the analysis to run out of memory.
 *
 * [withOutOfMemoryGuidance] keeps the original failure as the cause of the guidance it swaps in, so
 * this stays true afterwards. That's what lets the failure screen tell an analysis that ran out of
 * memory apart from one that hit a bug worth reporting.
 */
internal val HeapAnalysisFailure.isOutOfMemory: Boolean
  get() = generateSequence(exception.cause!!) { it.cause }
    .any { it is OutOfMemoryError }

/**
 * Returns this failure with its exception replaced by one that lists what can be done about running
 * out of memory, or this failure unchanged if that's not what went wrong.
 */
internal fun HeapAnalysisFailure.withOutOfMemoryGuidance(): HeapAnalysisFailure {
  if (!isOutOfMemory) {
    return this
  }
  val failureCause = exception.cause!!
  return copy(
    exception = HeapAnalysisException(
      RuntimeException(
        """
        Not enough memory to analyze heap. You can:
        - Kill the app then restart the analysis from the LeakCanary activity.
        - Increase the memory available to your debug app with largeHeap=true: https://developer.android.com/guide/topics/manifest/application-element#largeHeap
        - Set up LeakCanary to run in a separate process: https://square.github.io/leakcanary/recipes/#running-the-leakcanary-analysis-in-a-separate-process
        - Download the heap dump from the LeakCanary activity then run the analysis from your computer with shark-cli: https://square.github.io/leakcanary/shark/#shark-cli
      """.trimIndent(), failureCause
      )
    )
  )
}
