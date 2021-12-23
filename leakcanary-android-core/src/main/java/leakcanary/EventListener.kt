package leakcanary

import android.app.PendingIntent
import java.io.File
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.OnAnalysisProgressListener.Step

// TODO Document which thread events are called from and the blocking behavior / impact.
fun interface EventListener {

  sealed class Event {
    /**
     * Sent from the "LeakCanary-Heap-Dump" HandlerThread.
     */
    object DumpingHeap : Event()

    /**
     * Sent from the "LeakCanary-Heap-Dump" HandlerThread.
     */
    class HeapDumped(
      val file: File,
      val durationMillis: Long,
      val reason: String
    ) : Event()

    /**
     * Sent from the "LeakCanary-Heap-Dump" HandlerThread.
     */
    class HeapDumpFailed(val exception: Throwable, val willRetryLater: Boolean) : Event()

    /**
     * [progressPercent] is a value between [0..1]
     */
    class HeapAnalysisProgress(val step: Step, val progressPercent: Double) : Event()

    sealed class HeapAnalysisDone<T : HeapAnalysis>(
      val heapAnalysis: T,
      val showIntent: PendingIntent
    ) : Event() {
      class HeapAnalysisSucceeded(
        heapAnalysis: HeapAnalysisSuccess,
        val unreadLeakSignatures: Set<String>,
        showIntent: PendingIntent
      ) : HeapAnalysisDone<HeapAnalysisSuccess>(heapAnalysis, showIntent)

      class HeapAnalysisFailed(
        heapAnalysis: HeapAnalysisFailure,
        showIntent: PendingIntent
      ) : HeapAnalysisDone<HeapAnalysisFailure>(heapAnalysis, showIntent)
    }
  }

  fun onEvent(event: Event)
}
