package shark.explorer.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.SemanticDominatorTreemap

/**
 * What happens to a read of the heap dump when whoever asked for it stops waiting for the answer.
 *
 * Reads queue on one thread, so this is what keeps that queue short: the pointer crossing a treemap and a
 * window being dragged each ask a question per size they pass through, and the answer wanted is the last
 * one. A read that ran to its end after the question was withdrawn would put every abandoned one in front
 * of it.
 *
 * A timeout on each test because the failure being ruled out is a read that never stops, which without one
 * hangs the suite rather than failing it.
 */
class HeapDumpSessionTest {

  @get:Rule val testFolder = TemporaryFolder()

  /** Which reads started, ended and gave up, which is all this can be observed by. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  @Test(timeout = TEST_TIMEOUT_MILLIS)
  fun `a read nobody is waiting for any more gives up`(): Unit = runBlocking {
    HeapDumpSession.open(testFolder.cachedPayloadHeapDump()) {}.use { session ->
      val endless = startEndlessRead(session)

      endless.cancelAndJoin()

      // Which nothing else would have ended, since the read itself never finishes.
      assertThat(logged).anyMatch { "Gave up reading $ENDLESS_READ" in it }
      // And the thread is free for the next question, which is the whole point of giving up.
      assertThat(session.read("the sizes") { it.sizes }.totalObjectCount).isGreaterThan(0)
    }
  }

  @Test(timeout = TEST_TIMEOUT_MILLIS)
  fun `a read given up on while it was queued never starts`(): Unit = runBlocking {
    HeapDumpSession.open(testFolder.cachedPayloadHeapDump()) {}.use { session ->
      val endless = startEndlessRead(session)
      val queued = launch { session.read(QUEUED_READ) { it.sizes } }
      // Long enough for it to be queued, which is as far as it gets while the thread is taken.
      yield()

      queued.cancel()
      endless.cancelAndJoin()
      queued.join()

      // A size dragged through is never read rather than read and thrown away: what queues is the
      // coroutine, and one that has been cancelled doesn't get onto the thread at all.
      assertThat(logged).noneMatch { "Reading $QUEUED_READ" in it }
    }
  }

  /**
   * Reads the heap dump over and over, and returns once that has started: a read can only be observed
   * giving up while it's running, and this one has no end of its own to reach first.
   */
  private suspend fun CoroutineScope.startEndlessRead(session: HeapDumpSession): Job {
    val objectIds = session.read("some objects to read over and over") { it.tree.objectsNearTheRoot() }
    check(objectIds.isNotEmpty()) {
      "${session.heapDumpFile.name} has no object under the root of its tree to read over and over"
    }
    val hasStarted = CompletableDeferred<Unit>()
    val read = launch {
      session.read(ENDLESS_READ) { explorer ->
        while (true) {
          hasStarted.complete(Unit)
          objectIds.forEach { explorer.tree.summarize(it) }
        }
      }
    }
    hasStarted.await()
    return read
  }

  /**
   * Objects from the top of the tree, to be read over and over.
   *
   * Objects rather than anything else because reading is what a read gives up at — Shark asks whether to
   * stop on every record it reads — and positive ids because the tree gathers small children into piles,
   * which stand for objects and are none.
   */
  private fun SemanticDominatorTreemap.objectsNearTheRoot(): List<Long> =
    children(root).flatMap { child -> children(child) + child }.filter { it > 0L }

  companion object {
    private const val ENDLESS_READ = "every object, over and over"
    private const val QUEUED_READ = "the sizes, from behind a read that never ends"
    private const val TEST_TIMEOUT_MILLIS = 60_000L
  }
}
