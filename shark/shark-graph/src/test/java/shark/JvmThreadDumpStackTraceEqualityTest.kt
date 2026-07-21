package shark

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph

/**
 * Captures the live JVM stack trace of a worker thread parked at a known point, then dumps the
 * heap and reconstructs that same thread's stack trace from the dump, and asserts the two agree
 * from the worker method down — both as [StackTraceElement]s and as rendered strings.
 *
 * This lives in its own class (rather than in [JvmThreadDumpTest]) because it needs the worker to
 * stay parked at a known point while both the live capture and the heap dump happen.
 *
 * The comparison starts at the worker method rather than the very top of the stack: the frames
 * above it are JVM park internals that aren't guaranteed to be recorded identically by the live
 * thread dump API and the HPROF format. Live [StackTraceElement]s are also normalized to drop the
 * module name / version that the JVM attaches to `java.base` frames (e.g. `Thread.run`), since
 * those can't be reconstructed from a heap dump. The worker is driven by a named [ParkingWorker]
 * rather than a lambda so that every frame from the worker method down is a real declared method
 * (a lambda shows up as a JVM hidden class whose name carries an environment-specific hash).
 */
class JvmThreadDumpStackTraceEqualityTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private val threadName = "shark-thread-dump-equality-test"
  private val workerMethodName = "blockHoldingSentinel"

  private lateinit var workerThread: Thread
  private lateinit var graph: CloseableHeapGraph
  private lateinit var liveStackTrace: List<StackTraceElement>
  private val parkedLatch = CountDownLatch(1)

  @Volatile
  private var keepRunning = true

  /** Named Runnable so the worker's caller frame is a real method, not a lambda hidden class. */
  private inner class ParkingWorker : Runnable {
    override fun run() {
      blockHoldingSentinel()
    }
  }

  @Before fun setUp() {
    workerThread = Thread(ParkingWorker(), threadName).apply {
      isDaemon = true
      start()
    }
    parkedLatch.await()
    waitUntilParked(workerThread)

    // Capture the live stack trace while the worker is parked, then dump the heap while it stays
    // parked at the exact same point, so the two views observe the same stack.
    liveStackTrace = workerThread.stackTrace.asList()

    val hprofFile = File(testFolder.newFolder(), "jvm_heap.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    graph = hprofFile.openHeapGraph()
  }

  @After fun tearDown() {
    if (::graph.isInitialized) {
      graph.close()
    }
    keepRunning = false
    if (::workerThread.isInitialized) {
      LockSupport.unpark(workerThread)
      workerThread.join(1.seconds.inWholeMilliseconds)
    }
  }

  private fun blockHoldingSentinel() {
    parkedLatch.countDown()
    while (keepRunning) {
      LockSupport.park()
    }
  }

  private fun waitUntilParked(thread: Thread) {
    val deadline = System.currentTimeMillis() + 5.seconds.inWholeMilliseconds
    while (thread.state != Thread.State.WAITING && System.currentTimeMillis() < deadline) {
      Thread.sleep(1)
    }
  }

  private fun dumpedThread() = graph.threads.single { it.name == threadName }

  /** Everything from the worker method down, so we skip the volatile park internals on top. */
  private fun List<StackTraceElement>.fromWorkerMethod(): List<StackTraceElement> {
    return dropWhile { it.methodName != workerMethodName }
  }

  /** Rebuilds an element without module name / version, which a heap dump can't reconstruct. */
  private fun StackTraceElement.withoutModule(): StackTraceElement {
    return StackTraceElement(className, methodName, fileName, lineNumber)
  }

  @Test fun `reconstructed stack trace equals live stack trace from worker frame down`() {
    val expected = liveStackTrace.fromWorkerMethod().map { it.withoutModule() }
    val actual = dumpedThread().toStackTrace().fromWorkerMethod()

    assertThat(actual).isEqualTo(expected)
  }

  @Test fun `reconstructed stack trace renders like live stack trace from worker frame down`() {
    val expected = liveStackTrace.fromWorkerMethod().joinToString("\n") { it.withoutModule().toString() }
    val actual = dumpedThread().toStackTrace().fromWorkerMethod().joinToString("\n")

    assertThat(actual).isEqualTo(expected)
  }
}
