package leakcanary.internal

import android.app.Notification
import android.content.Context
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import com.squareup.leakcanary.core.R
import leakcanary.EventListener.Event
import shark.CancelSignal
import shark.CanceledException
import shark.SharkLog

internal class HeapAnalyzerWorker(
  appContext: Context,
  workerParams: WorkerParameters
) :
  Worker(appContext, workerParams) {
  override fun doWork(): Result {
    val cancelSignal = CancelSignal {
      if (isStopped) "WorkManager stopped the heap analysis" else null
    }
    val heapDump = inputData.asEvent<Event.HeapDump>()
    try {
      val doneEvent =
        AndroidDebugHeapAnalyzer.runAnalysisBlocking(heapDump, cancelSignal) { event ->
          if (!isStopped) {
            InternalLeakCanary.sendEvent(event)
          }
        }
      if (!isStopped) {
        InternalLeakCanary.sendEvent(doneEvent)
      }
      return Result.success()
    } catch (canceled: CanceledException) {
      SharkLog.d { "Heap analysis for ${heapDump.file} was canceled: ${canceled.cancelReason}" }
      return Result.retry()
    }
  }

  override fun getForegroundInfoAsync(): ListenableFuture<ForegroundInfo> {
    return LazyImmediateFuture {
      applicationContext.heapAnalysisForegroundInfo()
    }
  }

  companion object {
    private const val EVENT_BYTES = "EVENT_BYTES"

    fun Event.asWorkerInputData(dataBuilder: Data.Builder = Data.Builder()) = dataBuilder
      .putByteArray(EVENT_BYTES, toByteArray())
      .build()

    inline fun <reified T> Data.asEvent(): T =
      Serializables.fromByteArray<T>(getByteArray(EVENT_BYTES)!!)!!

    fun Context.heapAnalysisForegroundInfo(): ForegroundInfo {
      val builder = Notification.Builder(this)
        .setContentTitle(getString(R.string.leak_canary_notification_analysing))
        .setContentText("LeakCanary is working.")
        .setProgress(100, 0, true)
      val notification =
        Notifications.buildNotification(this, builder, NotificationType.LEAKCANARY_LOW)
      return ForegroundInfo(
        R.id.leak_canary_notification_analyzing_heap,
        notification
      )
    }
  }
}
