package leakcanary.internal

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.multiprocess.RemoteListenableWorker
import com.google.common.util.concurrent.ListenableFuture
import leakcanary.AndroidDebugHeapAnalyzer
import leakcanary.internal.HeapAnalyzerWorker.Companion.asHeapDumpEvent

internal class RemoteHeapAnalyzerWorker(appContext: Context, workerParams: WorkerParameters) :
  RemoteListenableWorker(appContext, workerParams) {

  private val backgroundHandler by lazy {
    val handlerThread = HandlerThread("HeapAnalyzer")
    handlerThread.start()
    Handler(handlerThread.looper)
  }

  override fun startRemoteWork(): ListenableFuture<Result> {
    val heapDump = inputData.asHeapDumpEvent()
    val result = SettableFuture.create<Result>()
    backgroundHandler.post {
      AndroidDebugHeapAnalyzer.runAnalysisBlocking(heapDump)
      result.set(Result.success())
    }
    return result
  }
}
