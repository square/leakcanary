package com.squareup.leakcanary

import com.squareup.leakcanary.HeapDumper.RETRY_LATER
import com.squareup.leakcanary.Retryable.Result.DONE
import com.squareup.leakcanary.Retryable.Result.RETRY
import java.util.concurrent.TimeUnit.NANOSECONDS

/**
 * Temporary name for the thing that brings LeakCanary together.
 *
 * Watches references that should become weakly reachable. When the {@link RefWatcher} detects that
 * a reference might not be weakly reachable when it should, this class triggers the
 * {@link HeapDumper}.
 *
 * <p>This class is thread-safe: you can call {@link #watch(Object)} from any thread.
 */
class RuntimeGlue constructor(
  private val refWatcher: RefWatcher,
  private val watchExecutor: WatchExecutor,
  private val debuggerControl: DebuggerControl,
  private val gcTrigger: GcTrigger,
  private val heapDumper: HeapDumper,
  private val heapdumpListener: HeapDump.Listener,
  private val heapDumpBuilder: HeapDump.Builder
) {

  fun watchForLeaks() {
    refWatcher.addNewRefListener {
      watchExecutor.execute { ensureGone() }
    }
  }

  private fun ensureGone(
  ): Retryable.Result {
    val gcStartNanoTime = System.nanoTime()

    if (debuggerControl.isDebuggerAttached) {
      // The debugger can create false leaks.
      return RETRY
    }

    if (refWatcher.isEmpty) {
      return DONE
    }

    // TODO Configure. Instead of one runnable per weak ref, it shoud be just the one,
    // scheduled for when the earliest ref was posted.
    if (!refWatcher.hasReferencesOlderThan(AndroidRefWatcherBuilder.DEFAULT_WATCH_DELAY_MILLIS)) {
      return RETRY
    }
    gcTrigger.runGc()
    if (refWatcher.hasReferencesOlderThan(AndroidRefWatcherBuilder.DEFAULT_WATCH_DELAY_MILLIS)) {
      val startDumpHeap = System.nanoTime()
      val gcDurationMs = NANOSECONDS.toMillis(startDumpHeap - gcStartNanoTime)

      val heapDumpFile = heapDumper.dumpHeap()
      if (heapDumpFile === RETRY_LATER) {
        // Could not dump the heap.
        return RETRY
      }
      val heapDumpDurationMs = NANOSECONDS.toMillis(System.nanoTime() - startDumpHeap)

      val heapDump = heapDumpBuilder.heapDumpFile(heapDumpFile)
          .gcDurationMs(gcDurationMs)
          .heapDumpDurationMs(heapDumpDurationMs)
          .build()

      heapdumpListener.analyze(heapDump)
    }
    return DONE
  }
}