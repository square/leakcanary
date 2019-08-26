package leakcanary.internal

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.squareup.leakcanary.core.R
import leakcanary.GcTrigger
import leakcanary.KeyedWeakReference
import leakcanary.LeakCanary.Config
import leakcanary.AppWatcher
import leakcanary.ObjectWatcher
import leakcanary.internal.NotificationReceiver.Action.CANCEL_NOTIFICATION
import leakcanary.internal.NotificationReceiver.Action.DUMP_HEAP
import leakcanary.internal.NotificationType.LEAKCANARY_LOW
import shark.SharkLog

@Suppress("TooManyFunctions")
internal class HeapDumpTrigger(
  private val application: Application,
  private val backgroundHandler: Handler,
  private val objectWatcher: ObjectWatcher,
  private val gcTrigger: GcTrigger,
  private val heapDumper: HeapDumper,
  private val configProvider: () -> Config
) {

  private val notificationManager
    get() =
      application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  private val applicationVisible
    get() = applicationInvisibleAt == -1L

  @Volatile
  private var checkScheduled: Boolean = false

  private var lastDisplayedRetainedObjectCount = 0

  private val scheduleDismissRetainedCountNotification = {
    dismissRetainedCountNotification()
  }

  private val scheduleDismissNoRetainedOnTapNotification = {
    dismissNoRetainedOnTapNotification()
  }

  /**
   * When the app becomes invisible, we don't dump the heap immediately. Instead we wait in case
   * the app came back to the foreground, but also to wait for new leaks that typically occur on
   * back press (activity destroy).
   */
  private val applicationInvisibleLessThanWatchPeriod: Boolean
    get() {
      val applicationInvisibleAt = applicationInvisibleAt
      return applicationInvisibleAt != -1L && SystemClock.uptimeMillis() - applicationInvisibleAt < AppWatcher.config.watchDurationMillis
    }

  @Volatile
  private var applicationInvisibleAt = -1L

  fun onApplicationVisibilityChanged(applicationVisible: Boolean) {
    if (applicationVisible) {
      applicationInvisibleAt = -1L
    } else {
      applicationInvisibleAt = SystemClock.uptimeMillis()
      scheduleRetainedObjectCheck("app became invisible", AppWatcher.config.watchDurationMillis)
    }
  }

  fun onObjectRetained() {
    scheduleRetainedObjectCheck("found new object retained")
  }

  private fun checkRetainedObjects(reason: String) {
    val config = configProvider()
    // A tick will be rescheduled when this is turned back on.
    if (!config.dumpHeap) {
      SharkLog.d { "No checking for retained object: LeakCanary.Config.dumpHeap is false" }
      return
    }
    SharkLog.d { "Checking retained object because $reason" }

    var retainedReferenceCount = objectWatcher.retainedObjectCount

    if (retainedReferenceCount > 0) {
      gcTrigger.runGc()
      retainedReferenceCount = objectWatcher.retainedObjectCount
    }

    if (checkRetainedCount(retainedReferenceCount, config.retainedVisibleThreshold)) return

    if (!config.dumpHeapWhenDebugging && DebuggerControl.isDebuggerAttached) {
      showRetainedCountWithDebuggerAttached(retainedReferenceCount)
      scheduleRetainedObjectCheck("debugger was attached", WAIT_FOR_DEBUG_MILLIS)
      SharkLog.d {
          "Not checking for leaks while the debugger is attached, will retry in $WAIT_FOR_DEBUG_MILLIS ms"
      }
      return
    }

    SharkLog.d { "Found $retainedReferenceCount retained references, dumping the heap" }
    val heapDumpUptimeMillis = SystemClock.uptimeMillis()
    KeyedWeakReference.heapDumpUptimeMillis = heapDumpUptimeMillis
    dismissRetainedCountNotification()
    val heapDumpFile = heapDumper.dumpHeap()
    if (heapDumpFile == null) {
      SharkLog.d { "Failed to dump heap, will retry in $WAIT_AFTER_DUMP_FAILED_MILLIS ms" }
      scheduleRetainedObjectCheck("failed to dump heap", WAIT_AFTER_DUMP_FAILED_MILLIS)
      showRetainedCountWithHeapDumpFailed(retainedReferenceCount)
      return
    }
    lastDisplayedRetainedObjectCount = 0
    objectWatcher.clearObjectsWatchedBefore(heapDumpUptimeMillis)

    HeapAnalyzerService.runAnalysis(application, heapDumpFile)
  }

  fun onDumpHeapReceived() {
    backgroundHandler.post {
      dismissNoRetainedOnTapNotification()
      gcTrigger.runGc()
      val retainedReferenceCount = objectWatcher.retainedObjectCount
      if (retainedReferenceCount == 0) {
        SharkLog.d { "No retained objects after GC" }
        @Suppress("DEPRECATION")
        val builder = Notification.Builder(application)
            .setContentTitle(
                application.getString(R.string.leak_canary_notification_no_retained_object_title)
            )
            .setContentText(
                application.getString(
                    R.string.leak_canary_notification_no_retained_object_content
                )
            )
            .setAutoCancel(true)
            .setContentIntent(NotificationReceiver.pendingIntent(application, CANCEL_NOTIFICATION))
        val notification =
          Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
        notificationManager.notify(
            R.id.leak_canary_notification_no_retained_object_on_tap, notification
        )
        backgroundHandler.postDelayed(
            scheduleDismissNoRetainedOnTapNotification,
            DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS
        )
        lastDisplayedRetainedObjectCount = 0
        return@post
      }

      val heapDumpUptimeMillis = SystemClock.uptimeMillis()
      KeyedWeakReference.heapDumpUptimeMillis = heapDumpUptimeMillis
      SharkLog.d { "Dumping the heap because user tapped notification" }

      val heapDumpFile = heapDumper.dumpHeap()
      if (heapDumpFile == null) {
        SharkLog.d { "Failed to dump heap" }
        showRetainedCountWithHeapDumpFailed(retainedReferenceCount)
        return@post
      }
      lastDisplayedRetainedObjectCount = 0
      objectWatcher.clearObjectsWatchedBefore(heapDumpUptimeMillis)
      HeapAnalyzerService.runAnalysis(application, heapDumpFile)
    }
  }

  private fun checkRetainedCount(
    retainedKeysCount: Int,
    retainedVisibleThreshold: Int
  ): Boolean {
    val countChanged = lastDisplayedRetainedObjectCount != retainedKeysCount
    lastDisplayedRetainedObjectCount = retainedKeysCount
    if (retainedKeysCount == 0) {
      SharkLog.d { "No retained objects" }
      if (countChanged) {
        showNoMoreRetainedObjectNotification()
      }
      return true
    }

    if (retainedKeysCount < retainedVisibleThreshold) {
      if (applicationVisible || applicationInvisibleLessThanWatchPeriod) {
        SharkLog.d {
            "Found $retainedKeysCount retained objects, which is less than the visible threshold of $retainedVisibleThreshold"
        }
        showRetainedCountBelowThresholdNotification(retainedKeysCount, retainedVisibleThreshold)
        scheduleRetainedObjectCheck(
            "Showing retained objects notification", WAIT_FOR_OBJECT_THRESHOLD_MILLIS
        )
        return true
      }
    }
    return false
  }

  private fun scheduleRetainedObjectCheck(reason: String) {
    if (checkScheduled) {
      SharkLog.d { "Already scheduled retained check, ignoring ($reason)" }
      return
    }
    checkScheduled = true
    backgroundHandler.post {
      checkScheduled = false
      checkRetainedObjects(reason)
    }
  }

  private fun scheduleRetainedObjectCheck(
    reason: String,
    delayMillis: Long
  ) {
    if (checkScheduled) {
      SharkLog.d { "Already scheduled retained check, ignoring ($reason)" }
      return
    }
    checkScheduled = true
    backgroundHandler.postDelayed({
      checkScheduled = false
      checkRetainedObjects(reason)
    }, delayMillis)
  }

  private fun showRetainedCountBelowThresholdNotification(
    objectCount: Int,
    retainedVisibleThreshold: Int
  ) {
    showRetainedCountNotification(
        objectCount, application.getString(
        R.string.leak_canary_notification_retained_visible, retainedVisibleThreshold
    )
    )
  }

  private fun showRetainedCountWithDebuggerAttached(objectCount: Int) {
    showRetainedCountNotification(
        objectCount,
        application.getString(R.string.leak_canary_notification_retained_debugger_attached)
    )
  }

  private fun showRetainedCountWithHeapDumpFailed(objectCount: Int) {
    showRetainedCountNotification(
        objectCount, application.getString(R.string.leak_canary_notification_retained_dump_failed)
    )
  }

  private fun showNoMoreRetainedObjectNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    if (!Notifications.canShowNotification) {
      return
    }
    val builder = Notification.Builder(application)
        .setContentTitle(
            application.getString(R.string.leak_canary_notification_no_retained_object_title)
        )
        .setContentText(
            application.getString(
                R.string.leak_canary_notification_no_retained_object_content
            )
        )
        .setAutoCancel(true)
        .setContentIntent(NotificationReceiver.pendingIntent(application, CANCEL_NOTIFICATION))
    val notification =
      Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
    notificationManager.notify(R.id.leak_canary_notification_retained_objects, notification)
    backgroundHandler.postDelayed(
        scheduleDismissRetainedCountNotification, DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS
    )
  }

  private fun showRetainedCountNotification(
    objectCount: Int,
    contentText: String
  ) {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    if (!Notifications.canShowNotification) {
      return
    }
    @Suppress("DEPRECATION")
    val builder = Notification.Builder(application)
        .setContentTitle(
            application.getString(R.string.leak_canary_notification_retained_title, objectCount)
        )
        .setContentText(contentText)
        .setAutoCancel(true)
        .setContentIntent(NotificationReceiver.pendingIntent(application, DUMP_HEAP))
    val notification =
      Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
    notificationManager.notify(R.id.leak_canary_notification_retained_objects, notification)
  }

  private fun dismissRetainedCountNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    notificationManager.cancel(R.id.leak_canary_notification_retained_objects)
  }

  private fun dismissNoRetainedOnTapNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissNoRetainedOnTapNotification)
    notificationManager.cancel(R.id.leak_canary_notification_no_retained_object_on_tap)
  }

  companion object {
    private const val WAIT_FOR_DEBUG_MILLIS = 20_000L
    private const val WAIT_AFTER_DUMP_FAILED_MILLIS = 5_000L
    private const val WAIT_FOR_OBJECT_THRESHOLD_MILLIS = 2_000L
    private const val DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS = 30_000L
  }

}