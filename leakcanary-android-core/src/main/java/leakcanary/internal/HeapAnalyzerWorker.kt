package leakcanary.internal

import android.app.Notification
import android.content.Context
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import com.squareup.leakcanary.core.R
import leakcanary.EventListener.Event

internal class HeapAnalyzerWorker(appContext: Context, workerParams: WorkerParameters) :
  Worker(appContext, workerParams) {
  override fun doWork(): Result {
    val doneEvent =
      AndroidDebugHeapAnalyzer.runAnalysisBlocking(inputData.asEvent()) { event ->
        InternalLeakCanary.sendEvent(event)
      }
    InternalLeakCanary.sendEvent(doneEvent)
    return Result.success()
  }

  override fun getForegroundInfoAsync(): ListenableFuture<ForegroundInfo> {
    return applicationContext.heapAnalysisForegroundInfoAsync()
  }

  companion object {
    private const val EVENT_BYTES = "EVENT_BYTES"

    fun Event.asWorkerInputData(dataBuilder: Data.Builder = Data.Builder()) = dataBuilder
      .putByteArray(EVENT_BYTES, toByteArray())
      .build()

    inline fun <reified T> Data.asEvent(): T =
      Serializables.fromByteArray<T>(getByteArray(EVENT_BYTES)!!)!!

    fun Context.heapAnalysisForegroundInfoAsync(): ListenableFuture<ForegroundInfo> {
      val infoFuture = SettableFuture.create<ForegroundInfo>()
      val builder = Notification.Builder(this)
        .setContentTitle(getString(R.string.leak_canary_notification_analysing))
        .setContentText("LeakCanary is working.")
        .setProgress(100, 0, true)
      val notification =
        Notifications.buildNotification(this, builder, NotificationType.LEAKCANARY_LOW)
      infoFuture.set(
        ForegroundInfo(
          R.id.leak_canary_notification_analyzing_heap,
          notification
        )
      )
      return infoFuture
    }
  }
}
