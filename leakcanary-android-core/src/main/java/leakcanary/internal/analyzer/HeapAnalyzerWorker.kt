package leakcanary.internal.analyzer

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import com.squareup.leakcanary.core.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import leakcanary.internal.NotificationType
import leakcanary.internal.Notifications
import leakcanary.internal.analyzer.BaseHeapAnalyzer.Companion.HEAPDUMP_FILE_PATH_EXTRA
import shark.SharkLog

/**
 * This worker should fix the foreground notification crash on Android 12+
 * [2192](https://github.com/square/leakcanary/issues/2192).
 *
 * As **WorkManager** is available from API 14,
 * the Service implementation can be completely replaced by this Worker.
 *
 * Being a [OneTimeWorkRequest], it should fire immediately on request & upon its init,
 * we can go ahead & safely show / update the notification with relevant progress.
 */
class HeapAnalyzerWorker(
  context: Context,
  params: WorkerParameters
) : CoroutineWorker(context, params) {

  private val notificationManager by lazy {
    applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  }

  /**
   * Using [CoroutineWorker.getId] will help in showing multiple notifications if the developer
   * wants to start multiple analysis when one is already running.
   *
   * Using [R.id.leak_canary_notification_analyzing_heap] will replace the ongoing notification.
   */
  private val notificationId: Int = id.mostSignificantBits.toInt()

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    if (!inputData.keyValueMap.containsKey(HEAPDUMP_FILE_PATH_EXTRA)) {
      SharkLog.d { "HeapAnalyzerWorker received a null or empty file path, ignoring." }
      return@withContext Result.failure()
    }

    BaseHeapAnalyzer(applicationContext, inputData) { percent, message ->
      val updatedNotification = buildForegroundNotification(100, percent, false, message)
      notificationManager.notify(notificationId, updatedNotification)
    }.startAnalyzing()

    return@withContext Result.Success().also { notificationManager.cancel(notificationId) }
  }

  // Required as the WorkRequest is marked expedited.
  override suspend fun getForegroundInfo(): ForegroundInfo {
    return ForegroundInfo(
      notificationId, buildForegroundNotification(
        max = 100, progress = 0, indeterminate = true,
        contentText = applicationContext.getString(R.string.leak_canary_notification_foreground_text)
      )
    )
  }

  private fun buildForegroundNotification(
    max: Int,
    progress: Int,
    indeterminate: Boolean,
    contentText: String
  ): Notification {
    val builder = Notification.Builder(applicationContext)
      .setContentTitle(applicationContext.getString(R.string.leak_canary_notification_analysing))
      .setContentText(contentText)
      .setOngoing(true)
      .setProgress(max, progress, indeterminate)
    return Notifications.buildNotification(
      applicationContext, builder,
      NotificationType.LEAKCANARY_LOW
    )
  }
}
