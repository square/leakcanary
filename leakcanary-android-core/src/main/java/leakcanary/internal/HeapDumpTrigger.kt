package leakcanary.internal

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources.NotFoundException
import android.os.Handler
import android.os.SystemClock
import com.squareup.leakcanary.core.R
import leakcanary.AppWatcher
import leakcanary.GcTrigger
import leakcanary.KeyedWeakReference
import leakcanary.LeakCanary.Config
import leakcanary.ObjectWatcher
import leakcanary.internal.InternalLeakCanary.onRetainInstanceListener
import leakcanary.internal.NotificationReceiver.Action.CANCEL_NOTIFICATION
import leakcanary.internal.NotificationReceiver.Action.DUMP_HEAP
import leakcanary.internal.NotificationType.LEAKCANARY_LOW
import leakcanary.internal.RetainInstanceEvent.CountChanged.DebuggerIsAttached
import leakcanary.internal.RetainInstanceEvent.CountChanged.DumpHappenedRecently
import leakcanary.internal.RetainInstanceEvent.CountChanged.BelowThreshold
import leakcanary.internal.RetainInstanceEvent.NoMoreObjects
import shark.AndroidResourceIdNames
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
  private var checkScheduledAt: Long = 0L

  private var lastDisplayedRetainedObjectCount = 0

  private var lastHeapDumpUptimeMillis = 0L

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
      // Scheduling for after watchDuration so that any destroyed activity has time to become
      // watch and be part of this analysis.
      scheduleRetainedObjectCheck(
          reason = "app became invisible",
          rescheduling = false,
          delayMillis = AppWatcher.config.watchDurationMillis
      )
    }
  }

  fun onObjectRetained() {
    scheduleRetainedObjectCheck(
        reason = "found new object retained",
        rescheduling = false
    )
  }

  private fun checkRetainedObjects(reason: String) {
    val config = configProvider()
    // A tick will be rescheduled when this is turned back on.
    if (!config.dumpHeap) {
      SharkLog.d { "Ignoring check for retained objects scheduled because $reason: LeakCanary.Config.dumpHeap is false" }
      return
    }

    var retainedReferenceCount = objectWatcher.retainedObjectCount

    if (retainedReferenceCount > 0) {
      gcTrigger.runGc()
      retainedReferenceCount = objectWatcher.retainedObjectCount
    }

    if (checkRetainedCount(retainedReferenceCount, config.retainedVisibleThreshold)) return

    if (!config.dumpHeapWhenDebugging && DebuggerControl.isDebuggerAttached) {
      onRetainInstanceListener.onEvent(DebuggerIsAttached)
      showRetainedCountNotification(
          objectCount = retainedReferenceCount,
          contentText = application.getString(
              R.string.leak_canary_notification_retained_debugger_attached
          )
      )
      scheduleRetainedObjectCheck(
          reason = "debugger is attached",
          rescheduling = true,
          delayMillis = WAIT_FOR_DEBUG_MILLIS
      )
      return
    }

    val now = SystemClock.uptimeMillis()
    val elapsedSinceLastDumpMillis = now - lastHeapDumpUptimeMillis
    if (elapsedSinceLastDumpMillis < WAIT_BETWEEN_HEAP_DUMPS_MILLIS) {
      onRetainInstanceListener.onEvent(DumpHappenedRecently)
      showRetainedCountNotification(
          objectCount = retainedReferenceCount,
          contentText = application.getString(R.string.leak_canary_notification_retained_dump_wait)
      )
      scheduleRetainedObjectCheck(
          reason = "previous heap dump was ${elapsedSinceLastDumpMillis}ms ago (< ${WAIT_BETWEEN_HEAP_DUMPS_MILLIS}ms)",
          rescheduling = true,
          delayMillis = WAIT_BETWEEN_HEAP_DUMPS_MILLIS - elapsedSinceLastDumpMillis
      )
      return
    }

    SharkLog.d { "Check for retained objects found $retainedReferenceCount objects, dumping the heap" }
    dismissRetainedCountNotification()
    dumpHeap(retainedReferenceCount, retry = true)
  }

  private fun dumpHeap(
    retainedReferenceCount: Int,
    retry: Boolean
  ) {
    saveResourceIdNamesToMemory()
    val heapDumpUptimeMillis = SystemClock.uptimeMillis()
    KeyedWeakReference.heapDumpUptimeMillis = heapDumpUptimeMillis
    val heapDumpFile = heapDumper.dumpHeap()
    if (heapDumpFile == null) {
      if (retry) {
        SharkLog.d { "Failed to dump heap, will retry in $WAIT_AFTER_DUMP_FAILED_MILLIS ms" }
        scheduleRetainedObjectCheck(
            reason = "failed to dump heap",
            rescheduling = true,
            delayMillis = WAIT_AFTER_DUMP_FAILED_MILLIS
        )
      } else {
        SharkLog.d { "Failed to dump heap, will not automatically retry" }
      }
      showRetainedCountNotification(
          objectCount = retainedReferenceCount,
          contentText = application.getString(
              R.string.leak_canary_notification_retained_dump_failed
          )
      )
      return
    }
    lastDisplayedRetainedObjectCount = 0
    lastHeapDumpUptimeMillis = SystemClock.uptimeMillis()
    objectWatcher.clearObjectsWatchedBefore(heapDumpUptimeMillis)
    HeapAnalyzerService.runAnalysis(application, heapDumpFile)
  }

  private fun saveResourceIdNamesToMemory() {
    val resources = application.resources
    AndroidResourceIdNames.saveToMemory(
        getResourceTypeName = { id ->
          try {
            resources.getResourceTypeName(id)
          } catch (e: NotFoundException) {
            null
          }
        },
        getResourceEntryName = { id ->
          try {
            resources.getResourceEntryName(id)
          } catch (e: NotFoundException) {
            null
          }
        })
  }

  fun onDumpHeapReceived(forceDump: Boolean) {
    backgroundHandler.post {
      dismissNoRetainedOnTapNotification()
      gcTrigger.runGc()
      val retainedReferenceCount = objectWatcher.retainedObjectCount
      if (!forceDump && retainedReferenceCount == 0) {
        SharkLog.d { "Ignoring user request to dump heap: no retained objects remaining after GC" }
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

      SharkLog.d { "Dumping the heap because user requested it" }
      dumpHeap(retainedReferenceCount, retry = false)
    }
  }

  private fun checkRetainedCount(
    retainedKeysCount: Int,
    retainedVisibleThreshold: Int
  ): Boolean {
    val countChanged = lastDisplayedRetainedObjectCount != retainedKeysCount
    lastDisplayedRetainedObjectCount = retainedKeysCount
    if (retainedKeysCount == 0) {
      SharkLog.d { "Check for retained object found no objects remaining" }
      if (countChanged) {
        onRetainInstanceListener.onEvent(NoMoreObjects)
        showNoMoreRetainedObjectNotification()
      }
      return true
    }

    if (retainedKeysCount < retainedVisibleThreshold) {
      if (applicationVisible || applicationInvisibleLessThanWatchPeriod) {
        if (countChanged) {
          onRetainInstanceListener.onEvent(BelowThreshold(retainedKeysCount))
        }
        showRetainedCountNotification(
            objectCount = retainedKeysCount,
            contentText = application.getString(
                R.string.leak_canary_notification_retained_visible, retainedVisibleThreshold
            )
        )
        scheduleRetainedObjectCheck(
            reason = "found only $retainedKeysCount retained objects (< $retainedVisibleThreshold while app visible)",
            rescheduling = true,
            delayMillis = WAIT_FOR_OBJECT_THRESHOLD_MILLIS
        )
        return true
      }
    }
    return false
  }

  private fun scheduleRetainedObjectCheck(
    reason: String,
    rescheduling: Boolean,
    delayMillis: Long = 0L
  ) {
    val checkCurrentlyScheduledAt = checkScheduledAt
    if (checkCurrentlyScheduledAt > 0) {
      val scheduledIn = checkCurrentlyScheduledAt - SystemClock.uptimeMillis()
      SharkLog.d { "Ignoring request to check for retained objects ($reason), already scheduled in ${scheduledIn}ms" }
      return
    } else {
      val verb = if (rescheduling) "Rescheduling" else "Scheduling"
      val delay = if (delayMillis > 0) " in ${delayMillis}ms" else ""
      SharkLog.d { "$verb check for retained objects${delay} because $reason" }
    }
    checkScheduledAt = SystemClock.uptimeMillis() + delayMillis
    backgroundHandler.postDelayed({
      checkScheduledAt = 0
      checkRetainedObjects(reason)
    }, delayMillis)
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
    private const val WAIT_BETWEEN_HEAP_DUMPS_MILLIS = 60_000L
  }

}