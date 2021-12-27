package leakcanary

import android.content.Intent
import java.io.File
import java.io.Serializable
import leakcanary.internal.SerializableIntent
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.OnAnalysisProgressListener.Step

fun interface EventListener {

  /**
   * Note: [Event] is [Serializable] for convenience but we currently make no guarantee
   * that the Serialization is backward / forward compatible across LeakCanary versions, so plan
   * accordingly. This is convenient for passing events around processes, and shouldn't be used
   * to store them.
   */
  sealed class Event(
    /**
     * Unique identifier for a related chain of event. The identifier for the events that run
     * before [HeapDump] gets reset right before [HeapDump] is sent.
     */
    val uniqueId: String
  ) : Serializable {
    /**
     * Sent from the "LeakCanary-Heap-Dump" HandlerThread.
     */
    class DumpingHeap(uniqueId: String) : Event(uniqueId)

    /**
     * Sent from the "LeakCanary-Heap-Dump" HandlerThread.
     */
    class HeapDump(
      uniqueId: String,
      val file: File,
      val durationMillis: Long,
      val reason: String
    ) : Event(uniqueId)

    /**
     * Sent from the "LeakCanary-Heap-Dump" HandlerThread.
     */
    class HeapDumpFailed(
      uniqueId: String,
      val exception: Throwable,
      val willRetryLater: Boolean
    ) : Event(uniqueId)

    /**
     * [progressPercent] is a value between [0..1]
     *
     * Sent from the thread performing the analysis.
     */
    class HeapAnalysisProgress(
      uniqueId: String,
      val step: Step,
      val progressPercent: Double
    ) : Event(uniqueId)

    /**
     * Sent from the thread performing the analysis.
     */
    sealed class HeapAnalysisDone<T : HeapAnalysis>(
      uniqueId: String,
      val heapAnalysis: T,
      showIntent: Intent
    ) : Event(uniqueId) {

      private val serializableShowIntent = SerializableIntent(showIntent)

      val showIntent: Intent
        get() = serializableShowIntent.intent

      class HeapAnalysisSucceeded(
        uniqueId: String,
        heapAnalysis: HeapAnalysisSuccess,
        val unreadLeakSignatures: Set<String>,
        showIntent: Intent
      ) : HeapAnalysisDone<HeapAnalysisSuccess>(uniqueId, heapAnalysis, showIntent)

      class HeapAnalysisFailed(
        uniqueId: String,
        heapAnalysis: HeapAnalysisFailure,
        showIntent: Intent
      ) : HeapAnalysisDone<HeapAnalysisFailure>(uniqueId, heapAnalysis, showIntent)
    }
  }

  /**
   * [onEvent] is always called from the thread the events are emitted from, which is documented
   * for each event. This enables you to potentially block a chain of events, waiting for some
   * pre work to be done.
   */
  fun onEvent(event: Event)
}
