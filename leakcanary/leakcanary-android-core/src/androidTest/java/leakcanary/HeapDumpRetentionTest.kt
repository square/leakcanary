package leakcanary

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import leakcanary.internal.LeakDirectoryProvider
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.HeapDumpTable
import leakcanary.internal.activity.db.ScopedLeaksDb
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import shark.HeapAnalysis
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure

/**
 * [LeakDirectoryProvider] deletes heap dumps to keep at most `maxStoredHeapDumps` of them. A heap
 * dump whose analysis hasn't run yet has to survive that, because that analysis can run long after
 * the process that created the heap dump is gone.
 */
internal class HeapDumpRetentionTest {

  @get:Rule
  var databaseRule = DatabaseRule()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val heapDumpDirectory
    get() = File(context.noBackupFilesDir, "leakcanary")

  @Before @After fun emptyHeapDumpDirectory() {
    heapDumpDirectory.listFiles()?.forEach { it.delete() }
  }

  @Test fun heap_dumps_under_the_limit_are_kept() {
    val kept = writeHeapDump("kept.hprof", lastModifiedMillis = 1000)

    newHeapDumpFile(maxStoredHeapDumps = 1)

    assertThat(kept).exists()
  }

  @Test fun analyzed_heap_dumps_are_deleted_before_ones_waiting_for_analysis() {
    val waiting = writeHeapDump("waiting.hprof", lastModifiedMillis = 1000)
    val analyzed = writeHeapDump("analyzed.hprof", lastModifiedMillis = 2000)
    markAnalyzed(analyzed)

    newHeapDumpFile(maxStoredHeapDumps = 1)

    assertThat(analyzed).doesNotExist()
    assertThat(waiting).exists()
  }

  @Test fun oldest_heap_dump_waiting_for_analysis_is_deleted_when_none_were_analyzed() {
    val oldest = writeHeapDump("oldest.hprof", lastModifiedMillis = 1000)
    val newest = writeHeapDump("newest.hprof", lastModifiedMillis = 2000)

    newHeapDumpFile(maxStoredHeapDumps = 1)

    assertThat(oldest).doesNotExist()
    assertThat(newest).exists()
  }

  @Test fun heap_dumps_with_no_analysis_are_the_ones_waiting_for_one() {
    val analyzed = writeHeapDump("analyzed.hprof", lastModifiedMillis = 1000)
    markAnalyzed(analyzed)
    val newerWaiting = writeHeapDump("newer-waiting.hprof", lastModifiedMillis = 3000)
    val olderWaiting = writeHeapDump("older-waiting.hprof", lastModifiedMillis = 2000)

    val waitingForAnalysis = LeakDirectoryProvider(context) { 7 }
      .heapDumpFilesWaitingForAnalysis()

    assertThat(waitingForAnalysis).containsExactly(olderWaiting, newerWaiting)
  }

  @Test fun deleting_a_heap_dump_waiting_for_analysis_records_that_it_was_waiting() {
    val waiting = writeHeapDump("waiting.hprof", lastModifiedMillis = 1000)
    writeHeapDump("newer.hprof", lastModifiedMillis = 2000)

    newHeapDumpFile(maxStoredHeapDumps = 1)

    assertThat(deletionReason(waiting))
      .contains("maxStoredHeapDumps limit of 1")
      .contains("still waiting to be analyzed")
  }

  @Test fun deleting_an_analyzed_heap_dump_records_that_it_was_analyzed() {
    val analyzed = writeHeapDump("analyzed.hprof", lastModifiedMillis = 1000)
    markAnalyzed(analyzed)
    writeHeapDump("newer.hprof", lastModifiedMillis = 2000)

    newHeapDumpFile(maxStoredHeapDumps = 1)

    assertThat(deletionReason(analyzed)).contains("the oldest one it had already analyzed")
  }

  @Test fun deleting_an_analysis_records_why_its_heap_dump_went_away() {
    val heapDumpFile = writeHeapDump("analyzed.hprof", lastModifiedMillis = 1000)
    val analysisId = markAnalyzed(heapDumpFile)

    ScopedLeaksDb.writableDatabase(context) { db ->
      HeapAnalysisTable.delete(db, analysisId, heapDumpFile)
    }

    assertThat(heapDumpFile).doesNotExist()
    assertThat(deletionReason(heapDumpFile)).contains("deleted from the LeakCanary UI")
  }

  @Test fun deleting_all_analyses_records_why_their_heap_dumps_went_away() {
    val heapDumpFile = writeHeapDump("analyzed.hprof", lastModifiedMillis = 1000)
    markAnalyzed(heapDumpFile)

    ScopedLeaksDb.writableDatabase(context) { db ->
      HeapAnalysisTable.deleteAll(db)
    }

    assertThat(heapDumpFile).doesNotExist()
    assertThat(deletionReason(heapDumpFile)).contains("All heap analyses were deleted")
  }

  @Test fun a_heap_dump_LeakCanary_never_deleted_has_no_recorded_reason() {
    val neverDeleted = writeHeapDump("kept.hprof", lastModifiedMillis = 1000)

    assertThat(rawDeletionReason(neverDeleted)).isNull()
  }

  @Test fun what_LeakCanary_records_about_heap_dumps_doesnt_grow_without_bound() {
    val heapDumpCount = 300
    ScopedLeaksDb.writableDatabase(context) { db ->
      repeat(heapDumpCount) { index ->
        HeapDumpTable.recordDeletion(db, File(heapDumpDirectory, "$index.hprof"), "Deleted.")
      }
      val rowCount = db.rawQuery("SELECT COUNT(*) FROM heap_dump", null).use { cursor ->
        cursor.moveToNext()
        cursor.getInt(0)
      }
      assertThat(rowCount).isLessThan(heapDumpCount)
    }
    assertThat(rawDeletionReason(File(heapDumpDirectory, "${heapDumpCount - 1}.hprof"))).isNotNull()
    assertThat(rawDeletionReason(File(heapDumpDirectory, "0.hprof"))).isNull()
  }

  private fun newHeapDumpFile(maxStoredHeapDumps: Int) {
    LeakDirectoryProvider(context) { maxStoredHeapDumps }.newHeapDumpFile()
  }

  private fun writeHeapDump(
    name: String,
    lastModifiedMillis: Long
  ): File {
    heapDumpDirectory.mkdirs()
    val heapDumpFile = File(heapDumpDirectory, name)
    heapDumpFile.writeText("Stands in for a heap dump: nothing here reads the contents.")
    check(heapDumpFile.setLastModified(lastModifiedMillis)) {
      "Could not set the last modified time of $heapDumpFile"
    }
    return heapDumpFile
  }

  /**
   * Stores an analysis of [heapDumpFile], which is what makes it a heap dump that isn't waiting for
   * one anymore. A failure is enough: the analysis ran, so the heap dump has been read.
   */
  private fun markAnalyzed(heapDumpFile: File): Long {
    val analysis: HeapAnalysis = HeapAnalysisFailure(
      heapDumpFile = heapDumpFile,
      createdAtTimeMillis = 42,
      analysisDurationMillis = 10,
      exception = HeapAnalysisException(RuntimeException("Boom"))
    )
    return ScopedLeaksDb.writableDatabase(context) { db ->
      HeapAnalysisTable.insert(db, analysis)
    }
  }

  private fun deletionReason(heapDumpFile: File): String =
    checkNotNull(rawDeletionReason(heapDumpFile)) {
      "No recorded deletion for $heapDumpFile"
    }

  private fun rawDeletionReason(heapDumpFile: File): String? =
    ScopedLeaksDb.readableDatabase(context) { db ->
      HeapDumpTable.retrieveDeletionReason(db, heapDumpFile)
    }
}
