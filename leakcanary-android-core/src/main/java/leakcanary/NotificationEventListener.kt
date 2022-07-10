package leakcanary

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.squareup.leakcanary.core.R
import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.DumpingHeap
import leakcanary.EventListener.Event.HeapAnalysisDone
import leakcanary.EventListener.Event.HeapAnalysisDone.HeapAnalysisSucceeded
import leakcanary.EventListener.Event.HeapAnalysisProgress
import leakcanary.EventListener.Event.HeapDump
import leakcanary.EventListener.Event.HeapDumpFailed
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.NotificationReceiver
import leakcanary.internal.NotificationType.LEAKCANARY_LOW
import leakcanary.internal.NotificationType.LEAKCANARY_MAX
import leakcanary.internal.Notifications
import leakcanary.internal.RetainInstanceEvent

private const val BACKGROUND_THREAD_NAME = "Leakcanary-Notification-Event-listener"
private const val DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS = 30_000L

object NotificationEventListener : EventListener {

  private val appContext = InternalLeakCanary.application
  private val notificationManager =
    appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  private val backgroundHandler: Handler
  private val scheduleDismissNoRetainedOnTapNotification = {
    dismissNoRetainedOnTapNotification()
  }
  private val scheduleDismissRetainedCountNotification = {
    dismissRetainedCountNotification()
  }

  init {
    val handlerThread = HandlerThread(BACKGROUND_THREAD_NAME)
    handlerThread.start()
    backgroundHandler = Handler(handlerThread.looper)
  }

  override fun onEvent(event: Event) {
    // TODO Unify Notifications.buildNotification vs Notifications.showNotification
    // We need to bring in the retained count notifications first though.
    if (!Notifications.canShowNotification) {
      return
    }
    when (event) {
      is DumpingHeap -> {
        // do like this on main thread
        dismissRetainedCountNotification()
        val dumpingHeap = appContext.getString(R.string.leak_canary_notification_dumping)
        val builder = Notification.Builder(appContext)
          .setContentTitle(dumpingHeap)
        val notification = Notifications.buildNotification(appContext, builder, LEAKCANARY_LOW)
        notificationManager.notify(R.id.leak_canary_notification_dumping_heap, notification)
      }
      is HeapDumpFailed, is HeapDump -> {
        notificationManager.cancel(R.id.leak_canary_notification_dumping_heap)
      }
      is HeapAnalysisProgress -> {
        val progress = (event.progressPercent * 100).toInt()
        val builder = Notification.Builder(appContext)
          .setContentTitle(appContext.getString(R.string.leak_canary_notification_analysing))
          .setContentText(event.step.humanReadableName)
          .setProgress(100, progress, false)
        val notification =
          Notifications.buildNotification(appContext, builder, LEAKCANARY_LOW)
        notificationManager.notify(R.id.leak_canary_notification_analyzing_heap, notification)
      }
      is HeapAnalysisDone<*> -> {
        notificationManager.cancel(R.id.leak_canary_notification_analyzing_heap)
        val contentTitle = if (event is HeapAnalysisSucceeded) {
          val heapAnalysis = event.heapAnalysis
          val retainedObjectCount = heapAnalysis.allLeaks.sumBy { it.leakTraces.size }
          val leakTypeCount = heapAnalysis.applicationLeaks.size + heapAnalysis.libraryLeaks.size
          val unreadLeakCount = event.unreadLeakSignatures.size
          appContext.getString(
            R.string.leak_canary_analysis_success_notification,
            retainedObjectCount,
            leakTypeCount,
            unreadLeakCount
          )
        } else {
          appContext.getString(R.string.leak_canary_analysis_failed)
        }
        val flags = if (Build.VERSION.SDK_INT >= 23) {
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
          PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(appContext, 1, event.showIntent, flags)
        showHeapAnalysisResultNotification(contentTitle, pendingIntent)
      }

      is Event.HeapDumpReceived -> {
        dismissNoRetainedOnTapNotification()
      }

      is Event.NoRetainedObjectFound -> {
        backgroundHandler.removeCallbacks(scheduleDismissNoRetainedOnTapNotification)

        val builder = Notification.Builder(appContext)
          .setContentTitle(
            appContext.getString(R.string.leak_canary_notification_no_retained_object_title)
          )
          .setContentText(
            appContext.getString(
              R.string.leak_canary_notification_no_retained_object_content
            )
          )
          .setAutoCancel(true)
          .setContentIntent(
            NotificationReceiver.pendingIntent(
              appContext,
              NotificationReceiver.Action.CANCEL_NOTIFICATION
            )
          )
        val notification =
          Notifications.buildNotification(appContext, builder, LEAKCANARY_LOW)
        notificationManager.notify(
          R.id.leak_canary_notification_no_retained_object_on_tap, notification
        )

        backgroundHandler.postDelayed(
          scheduleDismissNoRetainedOnTapNotification,
          DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS
        )
      }

      is Event.ShowRetainedCount -> {
        dismissRetainedCountNotification()

        @Suppress("DEPRECATION")
        val builder = Notification.Builder(appContext)
          .setContentTitle(
            appContext.getString(R.string.leak_canary_notification_retained_title, event.objectCount)
          )
          .setContentText(event.contentText)
          .setAutoCancel(true)
          .setContentIntent(NotificationReceiver.pendingIntent(appContext, NotificationReceiver.Action.DUMP_HEAP))
        val notification =
          Notifications.buildNotification(appContext, builder, LEAKCANARY_LOW)
        notificationManager.notify(R.id.leak_canary_notification_retained_objects, notification)

      }
    }
  }

  private fun dismissNoRetainedOnTapNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissNoRetainedOnTapNotification)
    notificationManager.cancel(R.id.leak_canary_notification_no_retained_object_on_tap)
  }

  private fun dismissRetainedCountNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    notificationManager.cancel(R.id.leak_canary_notification_retained_objects)
  }

  private fun showHeapAnalysisResultNotification(contentTitle: String, showIntent: PendingIntent) {
    val contentText = appContext.getString(R.string.leak_canary_notification_message)
    Notifications.showNotification(
      appContext, contentTitle, contentText, showIntent,
      R.id.leak_canary_notification_analysis_result,
      LEAKCANARY_MAX
    )
  }
}
