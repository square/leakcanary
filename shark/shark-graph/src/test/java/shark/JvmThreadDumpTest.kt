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
 * Dumps the heap of this live JVM while a known thread is parked inside a known method holding a
 * sentinel local, then asserts that the reconstructed thread dump exposes that thread, its stack
 * trace and its local variables. Each test asserts a single thing; the (expensive) heap dump is
 * done once in [setUp].
 */
class JvmThreadDumpTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  /** A distinctive type so we can recognize the parked thread's local variable in the heap dump. */
  class Sentinel

  private val threadName = "shark-thread-dump-test"
  private val workerMethodName = "blockHoldingSentinel"
  private val sentinelClassName = Sentinel::class.java.name

  private lateinit var workerThread: Thread
  private lateinit var graph: CloseableHeapGraph
  private val parkedLatch = CountDownLatch(1)

  @Volatile
  private var keepRunning = true

  @Before fun setUp() {
    workerThread = Thread({ blockHoldingSentinel() }, threadName).apply {
      isDaemon = true
      start()
    }
    // Wait until the worker reached blockHoldingSentinel (and thus has the sentinel local on its
    // stack) before dumping the heap.
    parkedLatch.await()

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

  @Suppress("UNUSED_VARIABLE")
  private fun blockHoldingSentinel() {
    val sentinel = Sentinel()
    parkedLatch.countDown()
    while (keepRunning) {
      LockSupport.park()
    }
    // Reference the sentinel after the loop so it stays a live local while the thread is parked.
    check(sentinel.hashCode() != 0 || true)
  }

  private fun dumpedThread() = graph.threads.single { it.name == threadName }

  @Test fun `finds thread by name`() {
    assertThat(graph.threads.map { it.name }.toList()).contains(threadName)
  }

  @Test fun `thread stack trace contains worker frame`() {
    val workerFrame = dumpedThread().stackTrace.firstOrNull { it.methodName == workerMethodName }

    assertThat(workerFrame).isNotNull
    assertThat(workerFrame!!.className).isEqualTo(JvmThreadDumpTest::class.java.name)
  }

  @Test fun `frame exposes local variable`() {
    val locals = dumpedThread().stackTrace.flatMap { it.locals }

    val sentinelLocal = locals
      .filterIsInstance<HeapObject.HeapInstance>()
      .firstOrNull { it.instanceClassName == sentinelClassName }

    assertThat(sentinelLocal).isNotNull
  }

  @Test fun `toStackTrace produces stack trace elements`() {
    val elements = dumpedThread().toStackTrace()

    assertThat(elements.map { it.methodName }).contains(workerMethodName)
  }

  @Test fun `stack trace as string renders jvm style`() {
    val rendered = dumpedThread().stackTraceAsString()

    assertThat(rendered).contains(threadName)
    assertThat(rendered).contains("\tat ${JvmThreadDumpTest::class.java.name}.$workerMethodName")
  }
}
