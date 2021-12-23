package leakcanary

import android.app.PendingIntent
import java.io.File
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.OnAnalysisProgressListener.Step

fun interface EventListener {

  sealed class Event {
    object DumpingHeap : Event()

    // TODO Ooooh maybe this is where we plug in the pipeline?
    class HeapDumped(
      val file: File,
      val durationMillis: Long
    ) : Event()

    class HeapDumpFailed(val exception: Throwable) : Event()

    class HeapAnalysisProgress(val step: Step) : Event()

    class HeapAnalysisSucceeded(
      val heapAnalysis: HeapAnalysisSuccess,
      val unreadLeakSignatures: Set<String>,
      val showIntent: PendingIntent
    ) : Event()

    class HeapAnalysisFailed(
      val heapAnalysis: HeapAnalysisFailure,
      val showIntent: PendingIntent
    ) : Event()
  }

  fun onEvent(event: Event)
}
