package leakcanary

import android.app.Instrumentation
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import leakcanary.GcTrigger.Default
import leakcanary.RetainedObjectsInstrumentationChecker.YesNo.No
import leakcanary.RetainedObjectsInstrumentationChecker.YesNo.Yes

class RetainedObjectsInstrumentationChecker(
  private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation(),
  private val objectWatcher: ObjectWatcher = AppWatcher.objectWatcher,
  private val retainedDelayMillis: Long = AppWatcher.retainedDelayMillis
) {

  sealed class YesNo {
    object Yes : YesNo()
    class No(val reason: String) : YesNo()
  }

  @Suppress("ReturnCount")
  fun shouldDumpHeapWaitingForRetainedObjects(): YesNo {
    val leakDetectionTime = SystemClock.uptimeMillis()

    if (!objectWatcher.hasWatchedObjects) {
      return No("No watched objects.")
    }

    instrumentation.waitForIdleSync()
    if (!objectWatcher.hasWatchedObjects) {
      return No("No watched objects after waiting for idle sync.")
    }

    Default.runGc()
    if (!objectWatcher.hasWatchedObjects) {
      return No("No watched objects after triggering an explicit GC.")
    }

    // Waiting for any delayed UI post (e.g. scroll) to clear. This shouldn't be needed, but
    // Android simply has way too many delayed posts that aren't canceled when views are detached.
    SystemClock.sleep(2000)

    if (!objectWatcher.hasWatchedObjects) {
      return No("No watched objects after delayed UI post is cleared.")
    }

    // Aaand we wait some more.
    // 4 seconds (2+2) is greater than the 3 seconds delay for
    // FINISH_TOKEN in android.widget.Filter
    SystemClock.sleep(2000)

    val endOfWatchDelay = retainedDelayMillis - (SystemClock.uptimeMillis() - leakDetectionTime)
    if (endOfWatchDelay > 0) {
      SystemClock.sleep(endOfWatchDelay)
    }

    Default.runGc()

    if (!objectWatcher.hasRetainedObjects) {
      return No("No retained objects after waiting for retained delay.")
    }
    KeyedWeakReference.heapDumpUptimeMillis = SystemClock.uptimeMillis()
    return Yes
  }

  fun clearObjectsWatchedBeforeHeapDump() {
    val heapDumpUptimeMillis = KeyedWeakReference.heapDumpUptimeMillis
    objectWatcher.clearObjectsWatchedBefore(heapDumpUptimeMillis)
  }
}
