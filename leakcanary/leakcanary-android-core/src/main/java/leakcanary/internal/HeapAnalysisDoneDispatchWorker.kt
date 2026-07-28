package leakcanary.internal

import android.content.Context
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import leakcanary.EventListener.Event.HeapAnalysisDone
import leakcanary.internal.HeapAnalyzerWorker.Companion.heapAnalysisForegroundInfo
import shark.SharkLog

/**
 * Dispatches the [leakcanary.EventListener.Event.HeapAnalysisDone] event for an analysis that ran
 * in the :leakcanary process.
 *
 * [RemoteHeapAnalyzerWorker] runs in the :leakcanary process, so events it sends only reach the
 * listeners configured in that process. This worker is enqueued as a dependent of
 * [RemoteHeapAnalyzerWorker] and, unlike it, runs in the main process, which is where
 * [leakcanary.LeakCanary.Config.eventListeners] are normally configured.
 *
 * The analysis itself isn't passed around: it's already stored in the LeakCanary database by the
 * time this runs, so all we need from the remote worker is the id to read it back with.
 *
 * Going through WorkManager rather than listening for the remote worker to finish also means the
 * event still gets dispatched if the main process dies while the analysis is running: WorkManager
 * persists this request and runs it once the main process is back up.
 */
internal class HeapAnalysisDoneDispatchWorker(
  appContext: Context,
  workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

  override fun doWork(): Result {
    val analysisId = inputData.getLong(ANALYSIS_ID, NO_ANALYSIS_ID)
    val uniqueId = inputData.getString(UNIQUE_ID)
    if (analysisId == NO_ANALYSIS_ID || uniqueId == null) {
      SharkLog.d { "Missing heap analysis id, not dispatching heap analysis done event" }
      return Result.failure()
    }
    val doneEvent = AndroidDebugHeapAnalyzer.retrieveAnalysisDoneEvent(uniqueId, analysisId)
      ?: return Result.failure()
    InternalLeakCanary.sendEvent(doneEvent)
    return Result.success()
  }

  override fun getForegroundInfoAsync(): ListenableFuture<ForegroundInfo> {
    return LazyImmediateFuture {
      applicationContext.heapAnalysisForegroundInfo()
    }
  }

  companion object {
    private const val ANALYSIS_ID = "ANALYSIS_ID"
    private const val UNIQUE_ID = "UNIQUE_ID"
    private const val NO_ANALYSIS_ID = -1L

    fun HeapAnalysisDone<*>.asDispatchWorkerOutputData() = Data.Builder()
      .putLong(ANALYSIS_ID, analysisId)
      .putString(UNIQUE_ID, uniqueId)
      .build()
  }
}
