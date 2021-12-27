package leakcanary

import android.content.Intent
import java.io.File
import java.io.Serializable
import leakcanary.internal.SerializableIntent
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.OnAnalysisProgressListener.Step

// TODO Document which thread events are called from and the blocking behavior / impact.
fun interface EventListener {

  /**
   * Note: [Event] is [Serializable] for convenience but we currently make no guarantee
   * that the Serialization is backward / forward compatible across LeakCanary versions, so plan
   * accordingly.
   */
  sealed class Event : Serializable {
    /**
     * Sent from the "LeakCanary-Heap-Dump" HandlerThread.
     */
    object DumpingHeap : Event()

    /**
     * Sent from the "LeakCanary-Heap-Dump" HandlerThread.
     */
    class HeapDump(
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
      showIntent: Intent
    ) : Event() {

      private val serializableShowIntent = SerializableIntent(showIntent)

      val showIntent: Intent
        get() = serializableShowIntent.intent

      class HeapAnalysisSucceeded(
        heapAnalysis: HeapAnalysisSuccess,
        val unreadLeakSignatures: Set<String>,
        showIntent: Intent
      ) : HeapAnalysisDone<HeapAnalysisSuccess>(heapAnalysis, showIntent)

      class HeapAnalysisFailed(
        heapAnalysis: HeapAnalysisFailure,
        showIntent: Intent
      ) : HeapAnalysisDone<HeapAnalysisFailure>(heapAnalysis, showIntent)
    }
  }

  fun onEvent(event: Event)
}
