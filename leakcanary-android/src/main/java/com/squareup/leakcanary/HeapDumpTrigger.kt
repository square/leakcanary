package com.squareup.leakcanary

import android.app.Application
import android.os.Handler
import android.os.SystemClock
import com.squareup.leakcanary.LeakCanary.Config
import com.squareup.leakcanary.internal.registerVisibilityListener
import leaksentry.RefWatcher

class HeapDumpTrigger(
  private val application: Application,
  private val backgroundHandler: Handler,
  private val debuggerControl: DebuggerControl,
  private val refWatcher: RefWatcher,
  private val leakDirectoryProvider: LeakDirectoryProvider,
  private val gcTrigger: GcTrigger,
  private val heapDumper: HeapDumper,
  private val heapdumpListener: HeapDump.Listener,
  private val configProvider: () -> Config
) {

  @Volatile
  var applicationVisible = false

  fun registerToVisibilityChanges() {
    application.registerVisibilityListener { applicationVisible ->
      this.applicationVisible = applicationVisible
      if (!applicationVisible) {
        scheduleTick("app became invisible")
      }
    }
  }

  fun onReferenceRetained() {
    scheduleTick("found new reference retained")
  }

  private fun tick(reason: String) {
    CanaryLog.d("Checking retained references because %s", reason)
    val config = configProvider()
    // A tick will be rescheduled when this is turned back on.
    if (!config.dumpHeap) {
      return
    }

    val minLeaks = if (applicationVisible) MIN_LEAKS_WHEN_VISIBLE else MIN_LEAKS_WHEN_NOT_VISIBLE
    var retainedKeys = refWatcher.retainedKeys
    if (retainedKeys.size < minLeaks) {
      // No need to scheduleTick, new refs always schedule one.
      CanaryLog.d(
          "Found %d retained references, which is less than the min of %d", retainedKeys.size, minLeaks
      )
      return
    }

    if (debuggerControl.isDebuggerAttached) {
      scheduleTick("debugger was attached", WAIT_FOR_DEBUG_MILLIS)
      CanaryLog.d(
          "Not checking for leaks while the debugger is attached, will retry in %d ms",
          WAIT_FOR_DEBUG_MILLIS
      )
      return
    }


    if (leakDirectoryProvider.hasPendingHeapDump()) {
      CanaryLog.d(
          "Leak Analysis in progress, will retry in %d ms", WAIT_FOR_PENDING_ANALYSIS_MILLIS
      )
      scheduleTick("had pending heap dump", WAIT_FOR_PENDING_ANALYSIS_MILLIS)
      return
    }
    val gcStartUptimeMillis = SystemClock.uptimeMillis()
    gcTrigger.runGc()
    val gcDurationMillis = SystemClock.uptimeMillis() - gcStartUptimeMillis


    retainedKeys = refWatcher.retainedKeys
    if (retainedKeys.size < minLeaks) {
      CanaryLog.d(
          "Found %d retained references after GC, which is less than the min of %d", retainedKeys.size,
          minLeaks
      )
      return
    }

    HeapDumpMemoryStore.setRetainedKeysForHeapDump(retainedKeys)

    CanaryLog.d("Found %d retained references, dumping the heap", retainedKeys.size)
    HeapDumpMemoryStore.heapDumpUptimeMillis = SystemClock.uptimeMillis()
    val heapDumpFile = heapDumper.dumpHeap()
    val heapDumpDurationMillis =
      SystemClock.uptimeMillis() - HeapDumpMemoryStore.heapDumpUptimeMillis

    if (heapDumpFile === HeapDumper.RETRY_LATER) {
      CanaryLog.d(
          "Failed to dump heap, will retry in %d ms", WAIT_FOR_HEAP_DUMPER_MILLIS
      )
      scheduleTick("failed to dump heap", WAIT_FOR_HEAP_DUMPER_MILLIS)
      return
    }
    refWatcher.removeRetainedKeys(retainedKeys)

    val heapDump = HeapDump.Builder()
        .heapDumpFile(heapDumpFile!!)
        .excludedRefs(config.excludedRefs)
        .gcDurationMs(gcDurationMillis)
        .heapDumpDurationMs(heapDumpDurationMillis)
        .computeRetainedHeapSize(config.computeRetainedHeapSize)
        .reachabilityInspectorClasses(config.reachabilityInspectorClasses)
        .build()
    heapdumpListener.analyze(heapDump)
  }

  private fun scheduleTick(reason: String) {
    backgroundHandler.post {
      tick(reason)
    }
  }

  private fun scheduleTick(reason: String, delayMillis: Long) {
    backgroundHandler.postDelayed({
      tick(reason)
    }, delayMillis)
  }

  companion object {
    const val LEAK_CANARY_THREAD_NAME = "LeakCanary-Heap-Dump"
    const val WAIT_FOR_PENDING_ANALYSIS_MILLIS = 20_000L
    const val WAIT_FOR_DEBUG_MILLIS = 20_000L
    const val WAIT_FOR_HEAP_DUMPER_MILLIS = 5_000L
    const val MIN_LEAKS_WHEN_VISIBLE = 5
    const val MIN_LEAKS_WHEN_NOT_VISIBLE = 1
  }

}