package shark.explorer.app

import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import shark.CancelSignal
import shark.CanceledException
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
  private val dispatcher: CoroutineDispatcher,
  /** Which read the heap dump is currently running, and whether it's still wanted. See [read]. */
  private val readInFlight: AtomicReference<ReadInFlight?>
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
   *
   * **Cancelling the coroutine that called this stops the read**, which matters because reads queue on
   * one thread: a window being dragged asks for a layout per size it passes through, and without this
   * the size it lands on waits behind every size it didn't. Shark does the stopping — it asks
   * [ReadInFlight] on every record it reads — so a read is only cancellable at the granularity of what
   * it reads, and one that computes for a while without reading stops when it next reads.
   */
  suspend fun <T> read(
    description: String,
    block: (HeapExplorer) -> T
  ): T = withContext(dispatcher) {
    SharkLog.d { "Reading $description" }
    val startNanos = System.nanoTime()
    // Set here rather than before dispatching, because a read the caller gave up on while it was queued
    // never starts: this coroutine is dispatched onto the heap dump thread only if it's still active.
    readInFlight.set(ReadInFlight(description, coroutineContext.job))
    try {
      block(explorer).also {
        SharkLog.d { "Read $description in ${millisSince(startNanos)} ms" }
      }
    } catch (canceled: CanceledException) {
      SharkLog.d { "Gave up reading $description after ${millisSince(startNanos)} ms" }
      throw canceled.asCancellation()
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Failed to read $description after ${millisSince(startNanos)} ms" }
      throw throwable
    } finally {
      // Safe without any coordination, because reads run one after another on this one thread: the next
      // one can't have set this while this one was running.
      readInFlight.set(null)
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
     *
     * Cancelling the coroutine that called this gives up on opening the heap dump, closes it and
     * releases the thread. Which is what closing a window while a 39 MB dump is being indexed should do,
     * and what it couldn't do before: the session would arrive at a coroutine that no longer existed,
     * with nobody left to [close] it.
     */
    suspend fun open(
      heapDumpFile: File,
      onProgress: (String) -> Unit
    ): HeapDumpSession {
      val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "heap-dump-${heapDumpFile.name}").apply { isDaemon = true }
      }
      val dispatcher = executor.asCoroutineDispatcher()
      val readInFlight = AtomicReference<ReadInFlight?>()
      return try {
        val explorer = withContext(dispatcher) {
          readInFlight.set(ReadInFlight("opening ${heapDumpFile.name}", coroutineContext.job))
          try {
            HeapExplorer.open(
              heapDumpFile = heapDumpFile,
              onProgress = onProgress,
              // One signal for the life of the heap dump, since the graph is given it as it's opened and
              // asks it on every record it reads afterwards. What changes is which read is in flight.
              cancelSignal = CancelSignal { readInFlight.get()?.cancelReasonOrNull() }
            )
          } finally {
            readInFlight.set(null)
          }
        }
        HeapDumpSession(explorer, executor, dispatcher, readInFlight)
      } catch (canceled: CanceledException) {
        // The heap dump closed itself on the way out of HeapExplorer.open, so there is only the thread
        // left to release.
        executor.shutdown()
        SharkLog.d { "Gave up opening ${heapDumpFile.name}" }
        throw canceled.asCancellation()
      } catch (throwable: Throwable) {
        executor.shutdown()
        throw throwable
      }
    }

    private fun millisSince(startNanos: Long): Long = NANOSECONDS.toMillis(System.nanoTime() - startNanos)
  }
}

/**
 * The read the heap dump thread is running, which Shark asks whether it should stop.
 *
 * It should as soon as [caller] is no longer active, because a read exists for exactly one coroutine: a
 * `LaunchedEffect` that has been relaunched or a window that has been closed has nowhere to put the
 * answer. Cheap and thread safe as a [CancelSignal] has to be — Shark asks this on every record it
 * reads.
 */
internal class ReadInFlight(
  private val description: String,
  private val caller: Job
) : CancelSignal {

  override fun cancelReasonOrNull(): String? =
    if (caller.isActive) null else "nothing is waiting for $description any more"
}

/**
 * Turns the heap dump having stopped into the cancellation that stopped it, because that is what
 * happened: a read is only ever cancelled by the coroutine that wanted it giving up. A coroutine being
 * cancelled has to resume with a [CancellationException] and not with a failure — a `LaunchedEffect`
 * resuming with one of those shows the window an error nobody hit.
 */
private fun CanceledException.asCancellation(): CancellationException =
  CancellationException(cancelReason).also { it.initCause(this) }
