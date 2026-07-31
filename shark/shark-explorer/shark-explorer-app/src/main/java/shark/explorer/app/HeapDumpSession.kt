package shark.explorer.app

import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit.NANOSECONDS
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.explorer.HeapDumpOrigin
import shark.explorer.HeapExplorer

/**
 * A [HeapExplorer] with every read of it confined to one background thread.
 *
 * Never the thread drawing the window, because a heap dump read takes anywhere from microseconds to
 * tens of seconds and a window that stops repainting for ten seconds looks broken. One thread rather
 * than several because a read is IO bound and the answers are wanted in the order they were asked for
 * — a [shark.HeapGraph] is read only and safe to read from several threads at once, so this could grow
 * a pool the day something needs one.
 *
 * Everything the UI needs therefore goes through [read], which hands the explorer over on that thread
 * and returns plain data. The thread is released by [close].
 */
class HeapDumpSession private constructor(
  private val explorer: HeapExplorer,
  private val executor: ExecutorService,
  private val dispatcher: CoroutineDispatcher
) : Closeable {

  val heapDumpFile: File get() = explorer.heapDumpFile

  /**
   * The device and process that wrote the heap dump, which was read while opening it.
   *
   * Not through [read], because nothing about it is read on demand: it's a handful of strings the
   * explorer already has.
   */
  val origin: HeapDumpOrigin get() = explorer.origin

  /**
   * Runs [block] against the heap dump on the thread that owns it, logging [description] as it starts
   * and as it comes back.
   *
   * Every read of an open heap dump goes through here, so this is also the trace of what the window was
   * doing: how long each read took says which one made it feel stuck, a read logged as started and
   * never as done is where a session that was killed or ran out of memory was, and a read that throws
   * is logged with what it was reading rather than with a stack trace alone.
   *
   * [description] therefore names what is being read, e.g. "the dominator of 0x12ab4000".
   */
  suspend fun <T> read(
    description: String,
    block: (HeapExplorer) -> T
  ): T = withContext(dispatcher) {
    SharkLog.d { "Reading $description" }
    val startNanos = System.nanoTime()
    try {
      block(explorer).also {
        SharkLog.d { "Read $description in ${millisSince(startNanos)} ms" }
      }
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Failed to read $description after ${millisSince(startNanos)} ms" }
      throw throwable
    }
  }

  /**
   * Closes the heap dump on its own thread, then releases the thread.
   *
   * Doesn't block, so that it can be called from a Compose `DisposableEffect`: the close is queued
   * behind whatever read is in flight rather than waited for.
   */
  override fun close() {
    SharkLog.d { "Closing ${heapDumpFile.name}" }
    try {
      executor.execute {
        try {
          explorer.close()
          SharkLog.d { "Closed ${heapDumpFile.name}" }
        } catch (throwable: Throwable) {
          // Nothing is waiting on this, so an exception here would otherwise reach an executor's
          // uncaught handler and be printed by nobody.
          SharkLog.d(throwable) { "Failed to close ${heapDumpFile.name}" }
        }
      }
    } catch (rejected: RejectedExecutionException) {
      SharkLog.d(rejected) { "${heapDumpFile.name} was already being closed" }
    }
    executor.shutdown()
  }

  companion object {
    /**
     * Opens [heapDumpFile] on a thread of its own. [onProgress] is called from that thread with a
     * description of each step as it starts.
     */
    suspend fun open(
      heapDumpFile: File,
      onProgress: (String) -> Unit
    ): HeapDumpSession {
      val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "heap-dump-${heapDumpFile.name}").apply { isDaemon = true }
      }
      val dispatcher = executor.asCoroutineDispatcher()
      return try {
        val explorer = withContext(dispatcher) { HeapExplorer.open(heapDumpFile, onProgress) }
        HeapDumpSession(explorer, executor, dispatcher)
      } catch (throwable: Throwable) {
        executor.shutdown()
        throw throwable
      }
    }

    private fun millisSince(startNanos: Long): Long = NANOSECONDS.toMillis(System.nanoTime() - startNanos)
  }
}
