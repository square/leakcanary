package leakcanary.internal

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.squareup.leakcanary.core.R
import leakcanary.CanaryLog
import leakcanary.GcTrigger
import leakcanary.KeyedWeakReference
import leakcanary.LeakCanary.Config
import leakcanary.LeakSentry
import leakcanary.RefWatcher
import leakcanary.internal.NotificationReceiver.Action.CANCEL_NOTIFICATION
import leakcanary.internal.NotificationReceiver.Action.DUMP_HEAP
import leakcanary.internal.NotificationType.LEAKCANARY_LOW

internal class HeapDumpTrigger(
  private val application: Application,
  private val backgroundHandler: Handler,
  private val refWatcher: RefWatcher,
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

  private var lastDisplayedRetainedInstanceCount = 0

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
      return applicationInvisibleAt != -1L && SystemClock.uptimeMillis() - applicationInvisibleAt < LeakSentry.config.watchDurationMillis
    }

  @Volatile
  private var applicationInvisibleAt = -1L

  fun onApplicationVisibilityChanged(applicationVisible: Boolean) {
    if (applicationVisible) {
      applicationInvisibleAt = -1L
    } else {
      applicationInvisibleAt = SystemClock.uptimeMillis()
      scheduleRetainedInstanceCheck("app became invisible", LeakSentry.config.watchDurationMillis)
    }
  }

  fun onReferenceRetained() {
    scheduleRetainedInstanceCheck("found new instance retained")
  }

  private fun checkRetainedInstances(reason: String) {
    val config = configProvider()
    // A tick will be rescheduled when this is turned back on.
    if (!config.dumpHeap) {
      CanaryLog.d("No checking for retained instance: LeakCanary.Config.dumpHeap is false")
      return
    }
    CanaryLog.d("Checking retained instances because %s", reason)

    var retainedReferenceCount = refWatcher.retainedInstanceCount

    if (retainedReferenceCount > 0) {
      gcTrigger.runGc()
      retainedReferenceCount = refWatcher.retainedInstanceCount
    }

    if (checkRetainedCount(retainedReferenceCount, config.retainedVisibleThreshold)) return

    if (!config.dumpHeapWhenDebugging && DebuggerControl.isDebuggerAttached) {
      showRetainedCountWithDebuggerAttached(retainedReferenceCount)
      scheduleRetainedInstanceCheck("debugger was attached", WAIT_FOR_DEBUG_MILLIS)
      CanaryLog.d(
          "Not checking for leaks while the debugger is attached, will retry in %d ms",
          WAIT_FOR_DEBUG_MILLIS
      )
      return
    }

    CanaryLog.d("Found %d retained references, dumping the heap", retainedReferenceCount)
    val heapDumpUptimeMillis = SystemClock.uptimeMillis()
    KeyedWeakReference.heapDumpUptimeMillis = heapDumpUptimeMillis
    dismissRetainedCountNotification()
    val heapDumpFile = heapDumper.dumpHeap()
    if (heapDumpFile == null) {
      CanaryLog.d("Failed to dump heap, will retry in %d ms", WAIT_AFTER_DUMP_FAILED_MILLIS)
      scheduleRetainedInstanceCheck("failed to dump heap", WAIT_AFTER_DUMP_FAILED_MILLIS)
      showRetainedCountWithHeapDumpFailed(retainedReferenceCount)
      return
    }
    lastDisplayedRetainedInstanceCount = 0
    refWatcher.removeInstancesRetainedBeforeHeapDump(heapDumpUptimeMillis)

    HeapAnalyzerService.runAnalysis(application, heapDumpFile)
  }

  fun onDumpHeapReceived() {
    backgroundHandler.post {
      dismissNoRetainedOnTapNotification()
      gcTrigger.runGc()
      val retainedReferenceCount = refWatcher.retainedInstanceCount
      if (retainedReferenceCount == 0) {
        CanaryLog.d("No retained instances after GC")
        val builder = Notification.Builder(application)
            .setContentTitle(
                application.getString(R.string.leak_canary_notification_no_retained_instance_title)
            )
            .setContentText(
                application.getString(
                    R.string.leak_canary_notification_no_retained_instance_content
                )
            )
            .setAutoCancel(true)
            .setContentIntent(NotificationReceiver.pendingIntent(application, CANCEL_NOTIFICATION))
        val notification =
          Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
        notificationManager.notify(
            R.id.leak_canary_notification_no_retained_instance_on_tap, notification
        )
        backgroundHandler.postDelayed(
            scheduleDismissNoRetainedOnTapNotification,
            DISMISS_NO_RETAINED_INSTANCE_NOTIFICATION_MILLIS
        )
        lastDisplayedRetainedInstanceCount = 0
        return@post
      }

      val heapDumpUptimeMillis = SystemClock.uptimeMillis()
      KeyedWeakReference.heapDumpUptimeMillis = heapDumpUptimeMillis
      CanaryLog.d("Dumping the heap because user tapped notification")

      val heapDumpFile = heapDumper.dumpHeap()
      if (heapDumpFile == null) {
        CanaryLog.d("Failed to dump heap")
        showRetainedCountWithHeapDumpFailed(retainedReferenceCount)
        return@post
      }
      lastDisplayedRetainedInstanceCount = 0
      refWatcher.removeInstancesRetainedBeforeHeapDump(heapDumpUptimeMillis)
      HeapAnalyzerService.runAnalysis(application, heapDumpFile)
    }
  }

  private fun checkRetainedCount(
    retainedKeysCount: Int,
    retainedVisibleThreshold: Int
  ): Boolean {
    val countChanged = lastDisplayedRetainedInstanceCount != retainedKeysCount
    lastDisplayedRetainedInstanceCount = retainedKeysCount
    if (retainedKeysCount == 0) {
      CanaryLog.d("No retained instances")
      if (countChanged) {
        showNoMoreRetainedInstanceNotification()
      }
      return true
    }

    if (retainedKeysCount < retainedVisibleThreshold) {
      if (applicationVisible || applicationInvisibleLessThanWatchPeriod) {
        CanaryLog.d(
            "Found %d retained instances, which is less than the visible threshold of %d",
            retainedKeysCount,
            retainedVisibleThreshold
        )
        showRetainedCountBelowThresholdNotification(retainedKeysCount, retainedVisibleThreshold)
        scheduleRetainedInstanceCheck(
            "Showing retained instance notification", WAIT_FOR_INSTANCE_THRESHOLD_MILLIS
        )
        return true
      }
    }
    return false
  }

  private fun scheduleRetainedInstanceCheck(reason: String) {
    if (checkScheduled) {
      CanaryLog.d("Already scheduled retained check, ignoring ($reason)")
      return
    }
    checkScheduled = true
    backgroundHandler.post {
      checkScheduled = false
      checkRetainedInstances(reason)
    }
  }

  private fun scheduleRetainedInstanceCheck(
    reason: String,
    delayMillis: Long
  ) {
    if (checkScheduled) {
      CanaryLog.d("Already scheduled retained check, ignoring ($reason)")
      return
    }
    checkScheduled = true
    backgroundHandler.postDelayed({
      checkScheduled = false
      checkRetainedInstances(reason)
    }, delayMillis)
  }

  private fun showRetainedCountBelowThresholdNotification(
    instanceCount: Int,
    retainedVisibleThreshold: Int
  ) {
    showRetainedCountNotification(
        instanceCount, application.getString(
        R.string.leak_canary_notification_retained_visible, retainedVisibleThreshold
    )
    )
  }

  private fun showRetainedCountWithDebuggerAttached(instanceCount: Int) {
    showRetainedCountNotification(
        instanceCount,
        application.getString(R.string.leak_canary_notification_retained_debugger_attached)
    )
  }

  private fun showRetainedCountWithHeapDumpFailed(instanceCount: Int) {
    showRetainedCountNotification(
        instanceCount, application.getString(R.string.leak_canary_notification_retained_dump_failed)
    )
  }

  private fun showNoMoreRetainedInstanceNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    if (!Notifications.canShowNotification) {
      return
    }
    val builder = Notification.Builder(application)
        .setContentTitle(
            application.getString(R.string.leak_canary_notification_no_retained_instance_title)
        )
        .setContentText(
            application.getString(
                R.string.leak_canary_notification_no_retained_instance_content
            )
        )
        .setAutoCancel(true)
        .setContentIntent(NotificationReceiver.pendingIntent(application, CANCEL_NOTIFICATION))
    val notification =
      Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
    notificationManager.notify(R.id.leak_canary_notification_retained_instances, notification)
    backgroundHandler.postDelayed(
        scheduleDismissRetainedCountNotification, DISMISS_NO_RETAINED_INSTANCE_NOTIFICATION_MILLIS
    )
  }

  private fun showRetainedCountNotification(
    instanceCount: Int,
    contentText: String
  ) {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    if (!Notifications.canShowNotification) {
      return
    }
    val builder = Notification.Builder(application)
        .setContentTitle(
            application.getString(R.string.leak_canary_notification_retained_title, instanceCount)
        )
        .setContentText(contentText)
        .setAutoCancel(true)
        .setContentIntent(NotificationReceiver.pendingIntent(application, DUMP_HEAP))
    val notification =
      Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
    notificationManager.notify(R.id.leak_canary_notification_retained_instances, notification)
  }

  private fun dismissRetainedCountNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
    notificationManager.cancel(R.id.leak_canary_notification_retained_instances)
  }

  private fun dismissNoRetainedOnTapNotification() {
    backgroundHandler.removeCallbacks(scheduleDismissNoRetainedOnTapNotification)
    notificationManager.cancel(R.id.leak_canary_notification_no_retained_instance_on_tap)
  }

  companion object {
    private const val WAIT_FOR_DEBUG_MILLIS = 20_000L
    private const val WAIT_AFTER_DUMP_FAILED_MILLIS = 5_000L
    private const val WAIT_FOR_INSTANCE_THRESHOLD_MILLIS = 2_000L
    private const val DISMISS_NO_RETAINED_INSTANCE_NOTIFICATION_MILLIS = 30_000L
  }

}