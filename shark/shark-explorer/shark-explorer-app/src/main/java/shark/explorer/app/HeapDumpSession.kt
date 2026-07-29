package shark.explorer.app

import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import shark.explorer.HeapExplorer

/**
 * A [HeapExplorer] with every read of it confined to one background thread.
 *
 * Two reasons it has to be exactly one, and never the thread drawing the window. A heap dump read
 * takes anywhere from microseconds to tens of seconds, and a window that stops repainting for ten
 * seconds looks broken. And [shark.HprofHeapGraph] has a single read cursor and a single object cache,
 * so two threads reading it at once corrupt each other's reads.
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

  /** Runs [block] against the heap dump on the thread that owns it. */
  suspend fun <T> read(block: (HeapExplorer) -> T): T = withContext(dispatcher) { block(explorer) }

  /**
   * Closes the heap dump on its own thread, then releases the thread.
   *
   * Doesn't block, so that it can be called from a Compose `DisposableEffect`: the close is queued
   * behind whatever read is in flight rather than waited for.
   */
  override fun close() {
    executor.execute { explorer.close() }
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
  }
}
