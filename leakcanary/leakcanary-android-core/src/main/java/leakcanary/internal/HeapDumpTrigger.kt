package leakcanary.internal

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources.NotFoundException
import android.os.Handler
import android.os.SystemClock
import com.squareup.leakcanary.core.R
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import leakcanary.AppWatcher
import leakcanary.EventListener.Event.DumpingHeap
import leakcanary.EventListener.Event.HeapDump
import leakcanary.EventListener.Event.HeapDumpFailed
import leakcanary.GcTrigger
import leakcanary.KeyedWeakReference
import leakcanary.LeakCanary.Config
import leakcanary.RetainedObjectTracker
import leakcanary.internal.HeapDumpControl.ICanHazHeap.Nope
import leakcanary.internal.HeapDumpControl.ICanHazHeap.NotifyingNope
import leakcanary.internal.InternalLeakCanary.onRetainInstanceListener
import leakcanary.internal.NotificationReceiver.Action.CANCEL_NOTIFICATION
import leakcanary.internal.NotificationReceiver.Action.DUMP_HEAP
import leakcanary.internal.NotificationType.LEAKCANARY_LOW
import leakcanary.internal.RetainInstanceEvent.CountChanged.BelowThreshold
import leakcanary.internal.RetainInstanceEvent.CountChanged.DumpHappenedRecently
import leakcanary.internal.RetainInstanceEvent.CountChanged.DumpingDisabled
import leakcanary.internal.RetainInstanceEvent.NoMoreObjects
import leakcanary.internal.friendly.measureDurationMillis
import shark.AndroidResourceIdNames
import shark.HeapAnalysis.Companion.DUMP_DURATION_UNKNOWN
import shark.SharkLog

internal class HeapDumpTrigger(
  private val application: Application,
  private val backgroundHandler: Handler,
  private val retainedObjectTracker: RetainedObjectTracker,
  private val gcTrigger: GcTrigger,
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

  private var checkedForDroppedAnalyses = false

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
      return applicationInvisibleAt != -1L && SystemClock.uptimeMillis() - applicationInvisibleAt < AppWatcher.retainedDelayMillis
    }

  @Volatile
  private var applicationInvisibleAt = -1L

  // Needs to be lazy because on Android 16, UUID.randomUUID().toString() will trigger a disk read
  // violation by calling RandomBitsSupplier.getUnixDeviceRandom()
  // Can't be lazy because this is a var.
  private var currentEventUniqueId: String? = null

  fun onApplicationVisibilityChanged(applicationVisible: Boolean) {
    if (applicationVisible) {
      applicationInvisibleAt = -1L
      // An analysis that was queued or running when its process died left behind a heap dump that
      // nothing is going to read, and since LeakCanary doesn't dump the heap again while one is
      // waiting, nothing would notice. Whether that happened is settled by the time this process
      // starts, so this only has to run once, and running it when the app becomes visible keeps it
      // out of app startup and out of the :leakcanary process, which has no UI to show.
      if (!checkedForDroppedAnalyses) {
        checkedForDroppedAnalyses = true
        backgroundHandler.post {
          dispatchAnalysisOfOldestHeapDumpWaitingForOne()
        }
      }
    } else {
      applicationInvisibleAt = SystemClock.uptimeMillis()
      // Scheduling for after watchDuration so that any destroyed activity has time to become
      // watch and be part of this analysis.
      scheduleRetainedObjectCheck(
        delayMillis = AppWatcher.retainedDelayMillis
      )
    }
  }

  private fun checkRetainedObjects() {
    val iCanHasHeap = HeapDumpControl.iCanHasHeap()

    val config = configProvider()

    if (iCanHasHeap is Nope) {
      if (iCanHasHeap is NotifyingNope) {
        // Before notifying that we can't dump heap, let's check if we still have retained object.
        var retainedReferenceCount = retainedObjectTracker.retainedObjectCount

        if (retainedReferenceCount > 0) {
          gcTrigger.runGc()
          retainedReferenceCount = retainedObjectTracker.retainedObjectCount
        }

        val nopeReason = iCanHasHeap.reason()
        val wouldDump = !checkRetainedCount(
          retainedReferenceCount, config.retainedVisibleThreshold, nopeReason
        )

        if (wouldDump) {
          val uppercaseReason = nopeReason[0].uppercaseChar() + nopeReason.substring(1)
          onRetainInstanceListener.onEvent(DumpingDisabled(uppercaseReason))
          showRetainedCountNotification(
            objectCount = retainedReferenceCount,
            contentText = uppercaseReason
          )
        }
      } else {
        SharkLog.d {
          application.getString(
            R.string.leak_canary_heap_dump_disabled_text, iCanHasHeap.reason()
          )
        }
      }
      return
    }

    var retainedReferenceCount = retainedObjectTracker.retainedObjectCount

    if (retainedReferenceCount > 0) {
      gcTrigger.runGc()
      retainedReferenceCount = retainedObjectTracker.retainedObjectCount
    }

    if (checkRetainedCount(retainedReferenceCount, config.retainedVisibleThreshold)) return

    val now = SystemClock.uptimeMillis()
    val elapsedSinceLastDumpMillis = now - lastHeapDumpUptimeMillis
    if (elapsedSinceLastDumpMillis < WAIT_BETWEEN_HEAP_DUMPS_MILLIS) {
      onRetainInstanceListener.onEvent(DumpHappenedRecently)
      showRetainedCountNotification(
        objectCount = retainedReferenceCount,
        contentText = application.getString(R.string.leak_canary_notification_retained_dump_wait)
      )
      scheduleRetainedObjectCheck(
        delayMillis = WAIT_BETWEEN_HEAP_DUMPS_MILLIS - elapsedSinceLastDumpMillis
      )
      return
    }

    // Dumping the heap again while an earlier heap dump is still waiting for its analysis would put
    // two analyses of two heap dumps in flight at once, each one holding a parsed heap in memory and
    // racing the other for CPU, and would fill up the stored heap dumps with heap dumps nothing has
    // read yet, until the oldest one gets deleted out from under the analysis that was going to read
    // it. So we wait, and while we wait we make sure that analysis is actually still coming.
    val waitingForAnalysis = dispatchAnalysisOfOldestHeapDumpWaitingForOne()
    if (waitingForAnalysis.isNotEmpty()) {
      SharkLog.d {
        "Waiting for the analysis of $waitingForAnalysis to finish before dumping the heap again"
      }
      val reason = application.getString(R.string.leak_canary_notification_analysis_wait)
      onRetainInstanceListener.onEvent(DumpingDisabled(reason))
      showRetainedCountNotification(
        objectCount = retainedReferenceCount,
        contentText = reason
      )
      scheduleRetainedObjectCheck(
        delayMillis = WAIT_FOR_PENDING_ANALYSIS_MILLIS
      )
      return
    }

    dismissRetainedCountNotification()
    val visibility = if (applicationVisible) "visible" else "not visible"
    dumpHeap(
      retainedReferenceCount = retainedReferenceCount,
      retry = true,
      reason = "$retainedReferenceCount retained objects, app is $visibility"
    )
  }

  /**
   * Returns the stored heap dumps that have no stored analysis, oldest first, and dispatches the
   * analysis of the oldest one.
   *
   * The analysis of a heap dump is dispatched once when the heap dump is taken and normally needs no
   * help after that: WorkManager persists the work it was given and re-runs an analysis that was
   * interrupted by the process dying. What it doesn't re-run is an analysis it considers failed, which
   * is what a dead `:leakcanary` process looks like to the main process, and it isn't there at all for
   * [leakcanary.BackgroundThreadHeapAnalyzer], the bare [Handler] LeakCanary falls back to when
   * WorkManager isn't in the classpath. Dispatching again is how those stop being a heap dump nobody
   * will ever read, and it costs nothing when the analysis is still coming: the analysis is enqueued
   * under a name unique to that heap dump, so WorkManager keeps the work it already has, and an
   * analysis that did complete is no longer waiting and so is never dispatched again.
   *
   * Only the oldest one is dispatched, because dispatching all of them would hand several analyses to
   * WorkManager at once, which runs them on a pool of 2 to 4 threads: several parsed heaps in memory
   * racing each other for CPU, which is the thing waiting for the analysis exists to prevent. The next
   * one is dispatched by the next call, once this one is no longer waiting.
   */
  private fun dispatchAnalysisOfOldestHeapDumpWaitingForOne(): List<File> {
    val waitingForAnalysis = InternalLeakCanary.createLeakDirectoryProvider(application)
      .heapDumpFilesWaitingForAnalysis()
    waitingForAnalysis.firstOrNull()?.let { oldestWaitingForAnalysis ->
      SharkLog.d {
        "Dispatching analysis of heap dump $oldestWaitingForAnalysis, which has no analysis"
      }
      InternalLeakCanary.sendEvent(
        HeapDump(
          uniqueId = UUID.randomUUID().toString(),
          file = oldestWaitingForAnalysis,
          durationMillis = DUMP_DURATION_UNKNOWN,
          reason = "Heap dump was taken earlier and its analysis never completed"
        )
      )
    }
    return waitingForAnalysis
  }

  private fun dumpHeap(
    retainedReferenceCount: Int,
    retry: Boolean,
    reason: String
  ) {
    val directoryProvider =
      InternalLeakCanary.createLeakDirectoryProvider(InternalLeakCanary.application)
    val heapDumpFile = directoryProvider.newHeapDumpFile()

    val durationMillis: Long
    if (currentEventUniqueId == null) {
      currentEventUniqueId = UUID.randomUUID().toString()
    }
    try {
      InternalLeakCanary.sendEvent(DumpingHeap(currentEventUniqueId!!))
      if (heapDumpFile == null) {
        throw RuntimeException("Could not create heap dump file")
      }
      saveResourceIdNamesToMemory()
      val heapDumpUptimeMillis = SystemClock.uptimeMillis()
      KeyedWeakReference.heapDumpUptimeMillis = heapDumpUptimeMillis
      durationMillis = measureDurationMillis {
        configProvider().heapDumper.dumpHeap(heapDumpFile)
      }
      if (heapDumpFile.length() == 0L) {
        throw RuntimeException("Dumped heap file is 0 byte length")
      }
      lastDisplayedRetainedObjectCount = 0
      lastHeapDumpUptimeMillis = SystemClock.uptimeMillis()
      retainedObjectTracker.clearObjectsTrackedBefore(heapDumpUptimeMillis.milliseconds)
      currentEventUniqueId = UUID.randomUUID().toString()
      InternalLeakCanary.sendEvent(HeapDump(currentEventUniqueId!!, heapDumpFile, durationMillis, reason))
    } catch (throwable: Throwable) {
      InternalLeakCanary.sendEvent(HeapDumpFailed(currentEventUniqueId!!, throwable, retry))
      if (retry) {
        scheduleRetainedObjectCheck(
          delayMillis = WAIT_AFTER_DUMP_FAILED_MILLIS
        )
      }
      showRetainedCountNotification(
        objectCount = retainedReferenceCount,
        contentText = application.getString(
          R.string.leak_canary_notification_retained_dump_failed
        )
      )
      return
    }
  }

  /**
   * Stores in memory the mapping of resource id ints to their corresponding name, so that the heap
   * analysis can label views with their resource id names.
   */
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
      val retainedReferenceCount = retainedObjectTracker.retainedObjectCount
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
      dumpHeap(retainedReferenceCount, retry = false, "user request")
    }
  }

  private fun checkRetainedCount(
    retainedKeysCount: Int,
    retainedVisibleThreshold: Int,
    nopeReason: String? = null
  ): Boolean {
    val countChanged = lastDisplayedRetainedObjectCount != retainedKeysCount
    lastDisplayedRetainedObjectCount = retainedKeysCount
    if (retainedKeysCount == 0) {
      if (countChanged) {
        SharkLog.d { "All retained objects have been garbage collected" }
        onRetainInstanceListener.onEvent(NoMoreObjects)
        showNoMoreRetainedObjectNotification()
      }
      return true
    }

    val applicationVisible = applicationVisible
    val applicationInvisibleLessThanWatchPeriod = applicationInvisibleLessThanWatchPeriod

    if (countChanged) {
      val whatsNext = if (applicationVisible) {
        if (retainedKeysCount < retainedVisibleThreshold) {
          "not dumping heap yet (app is visible & < $retainedVisibleThreshold threshold)"
        } else {
          if (nopeReason != null) {
            "would dump heap now (app is visible & >=$retainedVisibleThreshold threshold) but $nopeReason"
          } else {
            "dumping heap now (app is visible & >=$retainedVisibleThreshold threshold)"
          }
        }
      } else if (applicationInvisibleLessThanWatchPeriod) {
        val wait =
          AppWatcher.retainedDelayMillis - (SystemClock.uptimeMillis() - applicationInvisibleAt)
        if (nopeReason != null) {
          "would dump heap in $wait ms (app just became invisible) but $nopeReason"
        } else {
          "dumping heap in $wait ms (app just became invisible)"
        }
      } else {
        if (nopeReason != null) {
          "would dump heap now (app is invisible) but $nopeReason"
        } else {
          "dumping heap now (app is invisible)"
        }
      }

      SharkLog.d {
        val s = if (retainedKeysCount > 1) "s" else ""
        "Found $retainedKeysCount object$s retained, $whatsNext"
      }
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
          delayMillis = WAIT_FOR_OBJECT_THRESHOLD_MILLIS
        )
        return true
      }
    }
    return false
  }

  fun scheduleRetainedObjectCheck(
    delayMillis: Long = 0L
  ) {
    val checkCurrentlyScheduledAt = checkScheduledAt
    if (checkCurrentlyScheduledAt > 0) {
      return
    }
    checkScheduledAt = SystemClock.uptimeMillis() + delayMillis
    backgroundHandler.postDelayed({
      checkScheduledAt = 0
      checkRetainedObjects()
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
    internal const val WAIT_AFTER_DUMP_FAILED_MILLIS = 5_000L
    private const val WAIT_FOR_OBJECT_THRESHOLD_MILLIS = 2_000L
    private const val DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS = 30_000L
    private const val WAIT_BETWEEN_HEAP_DUMPS_MILLIS = 60_000L

    /**
     * How long to wait before checking again on a heap dump that hasn't been analyzed yet. An
     * analysis that finishes sooner than this only delays the next heap dump, which the minute
     * between heap dumps was going to delay anyway.
     */
    private const val WAIT_FOR_PENDING_ANALYSIS_MILLIS = 60_000L
  }
}
