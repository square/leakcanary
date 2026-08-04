package leakcanary

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import leakcanary.EventListener.Event.HeapAnalysisDone.HeapAnalysisFailed
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.AndroidDebugHeapAnalyzer
import leakcanary.internal.activity.db.HeapDumpTable
import leakcanary.internal.activity.db.ScopedLeaksDb
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import shark.HeapAnalysisFailure

/**
 * An analysis is queued on WorkManager, so it can run in a later process than the one that dumped
 * the heap, and find that the heap dump file is gone. What it can say about that comes from the
 * database, which is what makes the answer survive the process that did the deleting.
 */
internal class MissingHeapDumpFailureTest {

  @get:Rule
  var databaseRule = DatabaseRule()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

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

  @Test fun failure_names_the_recorded_reason_LeakCanary_deleted_the_heap_dump() {
    val heapDumpFile = File(context.noBackupFilesDir, "leakcanary/deleted.hprof")
    ScopedLeaksDb.writableDatabase(context) { db ->
      HeapDumpTable.recordDeletion(db, heapDumpFile, "A recorded reason.")
    }

    val failure = analyzeMissingHeapDump(heapDumpFile)

    assertThat(failure.exception.cause).hasMessageContaining("A recorded reason.")
  }

  @Test fun failure_says_LeakCanary_has_no_record_of_deleting_the_heap_dump() {
    val heapDumpFile = File(context.noBackupFilesDir, "leakcanary/vanished.hprof")

    val failure = analyzeMissingHeapDump(heapDumpFile)

    assertThat(failure.exception.cause).hasMessageContaining("no record of deleting it")
  }

  private fun analyzeMissingHeapDump(heapDumpFile: File): HeapAnalysisFailure {
    check(!heapDumpFile.exists()) {
      "$heapDumpFile should not exist"
    }
    val done = AndroidDebugHeapAnalyzer.runAnalysisBlocking(
      HeapDump(
        uniqueId = "unique-id",
        file = heapDumpFile,
        durationMillis = 10,
        reason = "Testing a heap dump that isn't there"
      )
    ) { }
    return (done as HeapAnalysisFailed).heapAnalysis
  }
}
