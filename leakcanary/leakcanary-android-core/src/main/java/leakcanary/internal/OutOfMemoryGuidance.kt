package leakcanary.internal

import shark.HeapAnalysisException
import shark.HeapAnalysisFailure

/**
 * Returns this failure with its exception replaced by one that lists what can be done about running
 * out of memory, or this failure unchanged if that's not what went wrong.
 *
 * The whole cause chain is searched rather than just the direct cause, because an
 * [OutOfMemoryError] rarely is the direct cause: Shark's hash maps catch it while allocating their
 * buffers and rethrow it wrapped in a [RuntimeException] that says which buffer they failed to
 * allocate, which is the most common way for the analysis to run out of memory.
 */
internal fun HeapAnalysisFailure.withOutOfMemoryGuidance(): HeapAnalysisFailure {
  val failureCause = exception.cause!!
  val outOfMemory = generateSequence(failureCause) { it.cause }
    .any { it is OutOfMemoryError }
  if (!outOfMemory) {
    return this
  }
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
