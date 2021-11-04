package leakcanary.internal.analyzer.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.squareup.leakcanary.core.R
import leakcanary.internal.NotificationType
import leakcanary.internal.Notifications

abstract class ForegroundWorker(
  context: Context,
  params: WorkerParameters
) : CoroutineWorker(context, params) {

  private val notificationManager by lazy {
    applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  }

  protected abstract fun getNotification(): Notification

  @StringRes
  protected abstract fun getNotificationTitleResId(): Int

  private fun getNotificationId(): Int {
    return id.mostSignificantBits.toInt()
  }

  protected fun showNotification(notification: Notification) {
    notificationManager.notify(getNotificationId(), notification)
  }

  protected fun removeNotification() {
    notificationManager.cancel(getNotificationId())
  }

  protected fun buildForegroundNotification(
    max: Int,
    progress: Int,
    indeterminate: Boolean,
    contentText: String
  ): Notification {
    val builder = Notification.Builder(applicationContext, "8888")
      .setContentTitle(applicationContext.getString(getNotificationTitleResId()))
      .setContentText(contentText)
      .setProgress(max, progress, indeterminate)

    if (SDK_INT >= 26) createChannel()

    return Notifications.buildNotification(
      applicationContext,
      builder, NotificationType.LEAKCANARY_LOW
    )
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun createChannel() {
    val channel = NotificationChannel(
      applicationContext.getString(R.string.leak_canary_notification_channel_id),
      "LeakCanary", NotificationManager.IMPORTANCE_HIGH
    )
    channel.description = "LeakCanary Notifications"
    notificationManager.createNotificationChannel(channel)
  }
}
