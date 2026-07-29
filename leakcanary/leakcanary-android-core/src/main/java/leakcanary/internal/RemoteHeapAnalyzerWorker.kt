package leakcanary.internal

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.multiprocess.RemoteListenableWorker
import com.google.common.util.concurrent.ListenableFuture
import leakcanary.BackgroundThreadHeapAnalyzer.heapAnalyzerThreadHandler
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.HeapAnalysisDoneDispatchWorker.Companion.asDispatchWorkerOutputData
import leakcanary.internal.HeapAnalyzerWorker.Companion.asEvent
import leakcanary.internal.HeapAnalyzerWorker.Companion.heapAnalysisForegroundInfo
import shark.CancelSignal
import shark.CanceledException
import shark.SharkLog

internal class RemoteHeapAnalyzerWorker(
  appContext: Context,
  workerParams: WorkerParameters
) :
  RemoteListenableWorker(appContext, workerParams) {

  override fun startRemoteWork(): ListenableFuture<Result> {
    val heapDump = inputData.asEvent<HeapDump>()
    val result = SettableFuture.create<Result>()
    heapAnalyzerThreadHandler.post {
      val cancelSignal = CancelSignal {
        if (result.isCancelled) "WorkManager canceled the remote heap analysis" else null
      }
      try {
        val doneEvent =
          AndroidDebugHeapAnalyzer.runAnalysisBlocking(heapDump, cancelSignal) { progressEvent ->
            if (!result.isCancelled) {
              InternalLeakCanary.sendEvent(progressEvent)
            }
          }
        // We're in the :leakcanary process here, so sending the done event would only reach the
        // listeners configured in this process. Instead we hand the analysis id over to
        // HeapAnalysisDoneDispatchWorker, which runs in the main process and dispatches from there.
        result.set(Result.success(doneEvent.asDispatchWorkerOutputData()))
      } catch (canceled: CanceledException) {
        SharkLog.d { "Remote heap analysis for ${heapDump.file} was canceled: ${canceled.cancelReason}" }
      }
    }
    return result
  }

  override fun getForegroundInfoAsync(): ListenableFuture<ForegroundInfo> {
    return LazyImmediateFuture {
      applicationContext.heapAnalysisForegroundInfo()
    }
  }
}
