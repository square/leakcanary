package leakcanary

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.HeapAnalysisDone.HeapAnalysisFailed
import leakcanary.EventListener.Event.HeapAnalysisDone.HeapAnalysisSucceeded
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.AndroidDebugHeapAnalyzer
import leakcanary.internal.HeapDumpTrigger
import leakcanary.internal.activity.db.HeapDumpTable
import leakcanary.internal.activity.db.ScopedLeaksDb
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import shark.CancelSignal
import shark.CanceledException
import shark.HprofWriterHelper
import shark.ValueHolder.IntHolder
import shark.dump

/**
 * A heap dump with no stored analysis is one whose analysis is still queued, or running, or was
 * dropped when the process it was queued in died, and LeakCanary can't tell which. So it asks for
 * that analysis again, which has to be safe when the analysis was never dropped at all, and has to
 * stop asking when analyzing that heap dump never completes.
 */
internal class RepeatedHeapAnalysisTest {

  @get:Rule
  var databaseRule = DatabaseRule()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val heapDumpDirectory
    get() = File(context.noBackupFilesDir, "leakcanary")

  private val configBeforeTest = LeakCanary.config

  @Before fun installAppWatcher() {
    // AndroidDebugHeapAnalyzer reads InternalLeakCanary.application, which requires an install.
    if (!AppWatcher.isInstalled) {
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        AppWatcher.manualInstall(
          context.applicationContext as Application,
          watchersToInstall = emptyList()
        )
      }
    }
  }

  @Before @After fun emptyHeapDumpDirectory() {
    heapDumpDirectory.listFiles()?.forEach { it.delete() }
  }

  @After fun restoreConfig() {
    LeakCanary.config = configBeforeTest
  }

  @Test fun analysis_of_a_heap_dump_waiting_for_one_is_dispatched_again() {
    val waitingForAnalysis = writeHeapDump()
    val dispatchedEvents = captureDispatchedEvents()

    onApplicationVisible()

    assertThat(dispatchedEvents.filterIsInstance<HeapDump>().map { it.file })
      .containsExactly(waitingForAnalysis)
  }

  /**
   * Several heap dumps waiting at once is what a process that died mid analysis more than once leaves
   * behind. Dispatching them all would hand WorkManager several analyses to run on its 2 to 4 thread
   * pool, which is the parallel analysis that waiting for the analysis exists to prevent.
   */
  @Test fun only_the_oldest_heap_dump_waiting_for_analysis_is_dispatched() {
    val older = writeHeapDump(name = "older.hprof", lastModifiedMillis = 1000)
    writeHeapDump(name = "newer.hprof", lastModifiedMillis = 2000)
    val dispatchedEvents = captureDispatchedEvents()

    onApplicationVisible()

    assertThat(dispatchedEvents.filterIsInstance<HeapDump>().map { it.file })
      .containsExactly(older)
  }

  @Test fun analysis_of_an_analyzed_heap_dump_is_not_dispatched_again() {
    val heapDumpFile = writeHeapDump()
    analyze(heapDumpFile)
    val dispatchedEvents = captureDispatchedEvents()

    onApplicationVisible()

    assertThat(dispatchedEvents.filterIsInstance<HeapDump>()).isEmpty()
  }

  @Test fun asking_again_gives_back_the_analysis_that_was_already_stored() {
    val heapDumpFile = writeHeapDump()
    val firstDone = analyze(heapDumpFile) as HeapAnalysisSucceeded

    val secondDone = analyze(heapDumpFile)

    assertThat(secondDone).isInstanceOf(HeapAnalysisSucceeded::class.java)
    assertThat((secondDone as HeapAnalysisSucceeded).analysisId).isEqualTo(firstDone.analysisId)
  }

  @Test fun asking_again_doesnt_store_a_second_analysis_of_the_same_heap_dump() {
    val heapDumpFile = writeHeapDump()

    analyze(heapDumpFile)
    analyze(heapDumpFile)

    assertThat(storedAnalysisCount()).isEqualTo(1)
  }

  @Test fun analyzing_a_heap_dump_is_given_up_on_when_it_never_completes() {
    val heapDumpFile = writeHeapDump()
    // What 3 analyses that got their process killed mid parse leave behind.
    repeat(3) { recordAnalysisStart(heapDumpFile) }

    val done = analyze(heapDumpFile)

    assertThat(done).isInstanceOf(HeapAnalysisFailed::class.java)
    assertThat((done as HeapAnalysisFailed).heapAnalysis.exception.cause)
      .hasMessageContaining("LeakCanary gave up on analyzing this heap dump")
  }

  @Test fun giving_up_on_a_heap_dump_leaves_the_file_in_place_to_be_shared() {
    val heapDumpFile = writeHeapDump()
    repeat(3) { recordAnalysisStart(heapDumpFile) }

    analyze(heapDumpFile)

    assertThat(heapDumpFile).exists()
  }

  @Test fun analyzing_a_heap_dump_that_completes_is_never_given_up_on() {
    val heapDumpFile = writeHeapDump()

    repeat(4) { analyze(heapDumpFile) }

    assertThat(analysisStartCount(heapDumpFile)).isEqualTo(1)
  }

  @Test fun a_canceled_analysis_doesnt_count_towards_giving_up() {
    val heapDumpFile = writeHeapDump()

    assertThatThrownBy {
      analyze(heapDumpFile, cancelSignal = CancelSignal { "Canceled by this test" })
    }.isInstanceOf(CanceledException::class.java)

    assertThat(analysisStartCount(heapDumpFile)).isZero()
  }

  private fun analyze(
    heapDumpFile: File,
    cancelSignal: CancelSignal = CancelSignal.NEVER
  ) = AndroidDebugHeapAnalyzer.runAnalysisBlocking(
    HeapDump(
      uniqueId = "unique-id",
      file = heapDumpFile,
      durationMillis = 10,
      reason = "Testing a heap dump analyzed more than once"
    ),
    cancelSignal
  ) { }

  /**
   * Runs what [HeapDumpTrigger] does when the app comes back to the foreground, which is where a new
   * process picks up the heap dumps an earlier one left waiting for an analysis.
   */
  private fun onApplicationVisible() {
    val handlerThread = HandlerThread("RepeatedHeapAnalysisTest")
    handlerThread.start()
    val backgroundHandler = Handler(handlerThread.looper)
    HeapDumpTrigger(
      application = context.applicationContext as Application,
      backgroundHandler = backgroundHandler,
      retainedObjectTracker = AppWatcher.objectWatcher,
      gcTrigger = GcTrigger.inProcess(),
      configProvider = { LeakCanary.config }
    ).onApplicationVisibilityChanged(applicationVisible = true)
    val done = CountDownLatch(1)
    backgroundHandler.post { done.countDown() }
    check(done.await(30, SECONDS)) {
      "Timed out waiting for the heap dump trigger's background work"
    }
    handlerThread.quit()
  }

  private fun captureDispatchedEvents(): List<Event> {
    val dispatchedEvents = mutableListOf<Event>()
    LeakCanary.config = LeakCanary.config.copy(
      eventListeners = listOf(EventListener { event -> dispatchedEvents += event })
    )
    return dispatchedEvents
  }

  private fun recordAnalysisStart(heapDumpFile: File) {
    ScopedLeaksDb.writableDatabase(context) { db ->
      HeapDumpTable.recordAnalysisStart(db, heapDumpFile)
    }
  }

  private fun analysisStartCount(heapDumpFile: File): Int {
    return ScopedLeaksDb.readableDatabase(context) { db ->
      db.rawQuery(
        "SELECT analysis_start_count FROM heap_dump WHERE file_path=?",
        arrayOf(heapDumpFile.absolutePath)
      ).use { cursor ->
        if (cursor.moveToNext()) cursor.getInt(0) else 0
      }
    }
  }

  private fun storedAnalysisCount(): Int {
    return ScopedLeaksDb.readableDatabase(context) { db ->
      db.rawQuery("SELECT COUNT(*) FROM heap_analysis", null).use { cursor ->
        cursor.moveToNext()
        cursor.getInt(0)
      }
    }
  }

  private fun writeHeapDump(
    name: String = "waiting-for-analysis.hprof",
    lastModifiedMillis: Long? = null,
    block: HprofWriterHelper.() -> Unit = {}
  ): File {
    heapDumpDirectory.mkdirs()
    val heapDumpFile = File(heapDumpDirectory, name)
    heapDumpFile.dump {
      "android.os.Build" clazz {
        staticField["MANUFACTURER"] = string("Samsing")
        staticField["ID"] = string("M4-rc20")
      }
      "android.os.Build\$VERSION" clazz {
        staticField["SDK_INT"] = IntHolder(47)
      }
      "Holder" clazz {
        staticField["leak"] = "com.example.Leaking" watchedInstance {}
      }
      block()
    }
    if (lastModifiedMillis != null) {
      check(heapDumpFile.setLastModified(lastModifiedMillis)) {
        "Could not set the last modified time of $heapDumpFile"
      }
    }
    return heapDumpFile
  }
}
