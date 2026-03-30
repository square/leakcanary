package leakcanary.internal

import android.content.Context
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.multiprocess.RemoteListenableWorker
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import leakcanary.BackgroundThreadHeapAnalyzer.heapAnalyzerThreadHandler
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.HeapAnalyzerWorker.Companion.asEvent
import leakcanary.internal.HeapAnalyzerWorker.Companion.heapAnalysisForegroundInfo
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
      val doneEvent = AndroidDebugHeapAnalyzer.runAnalysisBlocking(heapDump, isCanceled = {
        result.isCancelled
      }) { progressEvent ->
        if (!result.isCancelled) {
          InternalLeakCanary.sendEvent(progressEvent)
        }
      }
      if (result.isCancelled) {
        SharkLog.d { "Remote heap analysis for ${heapDump.file} was canceled" }
      } else {
        // Dispatch to any listeners configured in the background process.
        InternalLeakCanary.sendEvent(doneEvent)
        // Serialize the done event to a file so the main process can re-dispatch it
        // to its own listeners. WorkManager output data has a 10 KB limit, so we write
        // to a shared file instead and pass the path back.
        val outputData = try {
          val eventFile = File(applicationContext.filesDir, EVENT_FILE_PREFIX + heapDump.uniqueId)
          eventFile.writeBytes(doneEvent.toByteArray())
          Data.Builder()
            .putString(EVENT_FILE_KEY, eventFile.absolutePath)
            .build()
        } catch (e: Throwable) {
          SharkLog.d(e) { "Failed to serialize done event for main process" }
          Data.EMPTY
        }
        result.set(Result.success(outputData))
      }
    }
    return result
  }

  override fun getForegroundInfoAsync(): ListenableFuture<ForegroundInfo> {
    return LazyImmediateFuture {
      applicationContext.heapAnalysisForegroundInfo()
    }
  }

  companion object {
    const val EVENT_FILE_PREFIX = "leakcanary_remote_event_"
    const val EVENT_FILE_KEY = "leakcanary.remote.event_file"
  }
}
