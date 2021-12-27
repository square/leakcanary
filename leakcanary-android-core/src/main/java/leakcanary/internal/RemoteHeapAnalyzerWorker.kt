package leakcanary.internal

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.multiprocess.RemoteListenableWorker
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.RuntimeException
import leakcanary.BackgroundThreadHeapAnalyzer.heapAnalyzerThreadHandler
import leakcanary.EventListener.Event.HeapAnalysisDone
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.HeapAnalyzerWorker.Companion.asEvent
import shark.SharkLog

internal class RemoteHeapAnalyzerWorker(appContext: Context, workerParams: WorkerParameters) :
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
        InternalLeakCanary.sendEvent(doneEvent)
        result.set(Result.success())
      }
    }
    return result
  }
}
