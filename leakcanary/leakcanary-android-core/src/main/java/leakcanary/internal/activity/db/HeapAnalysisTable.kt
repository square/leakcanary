package leakcanary.internal.activity.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import leakcanary.internal.Serializables
import leakcanary.internal.toByteArray
import leakcanary.internal.friendly.checkNotMainThread
import leakcanary.internal.friendly.mainHandler
import org.intellij.lang.annotations.Language
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

internal object HeapAnalysisTable {

  /**
   * CopyOnWriteArrayList because registered listeners can remove themselves from this list while
   * iterating and invoking them, which would trigger a ConcurrentModificationException (see #2019).
   */
  private val updateListeners = CopyOnWriteArrayList<() -> Unit>()

  @Language("RoomSql")
  const val create = """CREATE TABLE heap_analysis
        (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        created_at_time_millis INTEGER,
        dump_duration_millis INTEGER DEFAULT -1,
        leak_count INTEGER DEFAULT 0,
        exception_summary TEXT DEFAULT NULL,
        heap_dump_file_path TEXT DEFAULT NULL,
        object BLOB
        )"""

  @Language("RoomSql")
  const val drop = "DROP TABLE IF EXISTS heap_analysis"

  private const val ANALYSIS_DELETED_REASON =
    "Its heap analysis was deleted from the LeakCanary UI."

  private const val ALL_ANALYSES_DELETED_REASON =
    "All heap analyses were deleted from the LeakCanary UI."

  fun onUpdate(block: () -> Unit): () -> Unit {
    updateListeners.add(block)
    return {
      updateListeners.remove(block)
    }
  }

  fun insert(
    db: SQLiteDatabase,
    heapAnalysis: HeapAnalysis
  ): Long {
    val values = ContentValues()
    values.put("created_at_time_millis", heapAnalysis.createdAtTimeMillis)
    values.put("dump_duration_millis", heapAnalysis.dumpDurationMillis)
    values.put("heap_dump_file_path", heapAnalysis.heapDumpFile.absolutePath)
    values.put("object", heapAnalysis.toByteArray())
    when (heapAnalysis) {
      is HeapAnalysisSuccess -> {
        val leakCount = heapAnalysis.applicationLeaks.size + heapAnalysis.libraryLeaks.size
        values.put("leak_count", leakCount)
      }
      is HeapAnalysisFailure -> {
        val cause = heapAnalysis.exception.cause!!
        val exceptionSummary = "${cause.javaClass.simpleName} ${cause.message}"
        values.put("exception_summary", exceptionSummary)
      }
    }

    return db.inTransaction {
      val heapAnalysisId = db.insertOrThrow("heap_analysis", null, values)
      if (heapAnalysis is HeapAnalysisSuccess) {
        heapAnalysis.allLeaks
          .forEach { leakingInstance ->
            LeakTable.insert(
              db, heapAnalysisId, leakingInstance
            )
          }
      }
      heapAnalysisId
    }.apply { notifyUpdateOnMainThread() }
  }

  private fun notifyUpdateOnMainThread() {
    checkNotMainThread()
    mainHandler.post {
      updateListeners.forEach { it() }
    }
  }

  inline fun <reified T : HeapAnalysis> retrieve(
    db: SQLiteDatabase,
    id: Long
  ): T? {
    return db.rawQuery(
      """
              SELECT
              object
              FROM heap_analysis
              WHERE id=$id
              """, null
    )
      .use { cursor ->
        if (cursor.moveToNext()) {
          val analysis = Serializables.fromByteArray<T>(cursor.getBlob(0))
          if (analysis == null) {
            delete(db, id, null)
          }
          analysis
        } else {
          null
        }
      }
  }

  /**
   * The absolute path of every heap dump file a stored analysis was run on. A heap dump file that
   * isn't in that set is still waiting to be analyzed, which is what the retention cleanup needs to
   * know before deleting it.
   */
  fun retrieveAnalyzedHeapDumpFilePaths(db: SQLiteDatabase): Set<String> {
    return db.rawQuery(
      """
          SELECT
          heap_dump_file_path
          FROM heap_analysis
          WHERE heap_dump_file_path IS NOT NULL
          """, null
    )
      .use { cursor ->
        val paths = mutableSetOf<String>()
        while (cursor.moveToNext()) {
          paths += cursor.getString(0)
        }
        paths
      }
  }

  fun retrieveAll(db: SQLiteDatabase): List<Projection> {
    return db.rawQuery(
      """
          SELECT
          id
          , created_at_time_millis
          , leak_count
          , exception_summary
          FROM heap_analysis
          ORDER BY created_at_time_millis DESC
          """, null
    )
      .use { cursor ->
        val all = mutableListOf<Projection>()
        while (cursor.moveToNext()) {
          val summary = Projection(
            id = cursor.getLong(0),
            createdAtTimeMillis = cursor.getLong(1),
            leakCount = cursor.getInt(2),
            exceptionSummary = cursor.getString(3)
          )
          all.add(summary)
        }
        all
      }
  }

  fun delete(
    db: SQLiteDatabase,
    heapAnalysisId: Long,
    heapDumpFile: File?
  ) {
    db.inTransaction {
      if (heapDumpFile != null) {
        deleteHeapDumpFile(db, heapDumpFile, ANALYSIS_DELETED_REASON)
      }
      db.delete("heap_analysis", "id=$heapAnalysisId", null)
      LeakTable.deleteByHeapAnalysisId(db, heapAnalysisId)
    }
    notifyUpdateOnMainThread()
  }

  fun deleteAll(db: SQLiteDatabase) {
    db.inTransaction {
      rawQuery(
        """
              SELECT
              id,
              heap_dump_file_path
              FROM heap_analysis
              """, null
      )
        .use { cursor ->
          val all = mutableListOf<Pair<Long, String?>>()
          while (cursor.moveToNext()) {
            all += cursor.getLong(0) to cursor.getString(1)
          }
          all.forEach { (id, heapDumpFilePath) ->
            db.delete("heap_analysis", "id=$id", null)
            LeakTable.deleteByHeapAnalysisId(db, id)
            if (heapDumpFilePath != null) {
              deleteHeapDumpFile(db, File(heapDumpFilePath), ALL_ANALYSES_DELETED_REASON)
            }
          }
        }
    }
    notifyUpdateOnMainThread()
  }

  /**
   * Deletes [heapDumpFile] and, when that worked, records why, so that an analysis still queued for
   * that file can say what happened to it rather than only that the file is gone. A delete that
   * returns false left the file in place, and overwriting an earlier reason with this one would
   * misattribute a deletion that already happened.
   */
  private fun deleteHeapDumpFile(
    db: SQLiteDatabase,
    heapDumpFile: File,
    reason: String
  ) {
    if (heapDumpFile.delete()) {
      HeapDumpDeletionTable.insert(db, heapDumpFile, reason)
    } else {
      SharkLog.d { "Could not delete heap dump file ${heapDumpFile.path}" }
    }
  }

  class Projection(
    val id: Long,
    val createdAtTimeMillis: Long,
    val leakCount: Int,
    val exceptionSummary: String?
  )
}