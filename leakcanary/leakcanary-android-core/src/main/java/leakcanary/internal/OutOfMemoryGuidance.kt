package leakcanary.internal

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure

private const val LARGE_HEAP_DOC_URL =
  "https://developer.android.com/guide/topics/manifest/application-element#largeHeap"
private const val SEPARATE_PROCESS_DOC_URL =
  "https://square.github.io/leakcanary/recipes/#running-the-leakcanary-analysis-in-a-separate-process"
private const val SHARK_CLI_DOC_URL = "https://square.github.io/leakcanary/shark/#shark-cli"

private const val BYTES_PER_MB = 1024 * 1024

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
 * How much memory the process running the analysis is allowed to use, and how much more it could get
 * from the `android:largeHeap="true"` manifest flag.
 */
internal class ProcessHeapLimit(
  val maxMemoryMb: Long,
  val largeHeapEnabled: Boolean,
  val largeHeapMaxMemoryMb: Int
) {
  companion object {
    fun read(context: Context): ProcessHeapLimit {
      val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      return ProcessHeapLimit(
        maxMemoryMb = Runtime.getRuntime().maxMemory() / BYTES_PER_MB,
        largeHeapEnabled =
          (context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0,
        largeHeapMaxMemoryMb = activityManager.largeMemoryClass
      )
    }
  }
}

/**
 * Returns this failure with its exception replaced by one that says how much memory the analysis was
 * allowed to use and what can be done about it running out, or this failure unchanged if running out
 * of memory isn't what went wrong.
 */
internal fun HeapAnalysisFailure.withOutOfMemoryGuidance(
  heapLimit: ProcessHeapLimit
): HeapAnalysisFailure {
  if (!isOutOfMemory) {
    return this
  }
  val failureCause = exception.cause!!
  return copy(
    exception = HeapAnalysisException(
      RuntimeException(outOfMemoryGuidance(heapLimit), failureCause)
    )
  )
}

/**
 * The guidance for an analysis that ran out of memory: how much memory it had, then what can be done
 * about that, most convenient first.
 */
internal fun outOfMemoryGuidance(heapLimit: ProcessHeapLimit): String {
  val options = mutableListOf("Kill the app then restart the analysis from the LeakCanary activity.")
  if (!heapLimit.largeHeapEnabled) {
    options += "Raise that limit to ${heapLimit.largeHeapMaxMemoryMb} MB by setting " +
      "android:largeHeap=\"true\" in the manifest of your debug app: $LARGE_HEAP_DOC_URL"
  }
  options += "Set up LeakCanary to run in a separate process: $SEPARATE_PROCESS_DOC_URL"
  options += "Download the heap dump from the LeakCanary activity then run the analysis from your " +
    "computer with shark-cli: $SHARK_CLI_DOC_URL"

  val largeHeapDetail = if (heapLimit.largeHeapEnabled) {
    ", with android:largeHeap=\"true\" already set"
  } else {
    ""
  }
  return "Not enough memory to analyze the heap dump: this process can use up to " +
    "${heapLimit.maxMemoryMb} MB$largeHeapDetail. You can:" +
    options.joinToString(prefix = "\n- ", separator = "\n- ")
}
