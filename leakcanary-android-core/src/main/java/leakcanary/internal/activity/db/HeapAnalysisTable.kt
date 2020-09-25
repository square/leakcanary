package leakcanary.internal.activity.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import leakcanary.internal.LeakDirectoryProvider
import leakcanary.internal.Serializables
import leakcanary.internal.toByteArray
import org.intellij.lang.annotations.Language
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog
import java.io.File

internal object HeapAnalysisTable {

  private val updateListeners = mutableListOf<() -> Unit>()

  private val mainHandler = Handler(Looper.getMainLooper())

  @Language("RoomSql")
  const val create = """CREATE TABLE heap_analysis
        (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        created_at_time_millis INTEGER,
        dump_duration_millis INTEGER DEFAULT -1,
        leak_count INTEGER DEFAULT 0,
        exception_summary TEXT DEFAULT NULL,
        object BLOB
        )"""

  @Language("RoomSql")
  const val drop = "DROP TABLE IF EXISTS heap_analysis"

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
    if (Looper.getMainLooper().thread === Thread.currentThread()) {
      throw UnsupportedOperationException(
          "Should not be called from the main thread"
      )
    }
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
    if (heapDumpFile != null) {
      AsyncTask.SERIAL_EXECUTOR.execute {
        val path = heapDumpFile.absolutePath
        val heapDumpDeleted = heapDumpFile.delete()
        if (heapDumpDeleted) {
          LeakDirectoryProvider.filesDeletedRemoveLeak += path
        } else {
          SharkLog.d { "Could not delete heap dump file ${heapDumpFile.path}" }
        }
      }
    }

    db.inTransaction {
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
              object
              FROM heap_analysis
              """, null
      )
          .use { cursor ->
            val all = mutableListOf<Pair<Long, HeapAnalysis>>()
            while (cursor.moveToNext()) {
              val id = cursor.getLong(0)
              val analysis = Serializables.fromByteArray<HeapAnalysis>(cursor.getBlob(1))
              if (analysis != null) {
                all += id to analysis
              }
            }
            all.forEach { (id, _) ->
              db.delete("heap_analysis", "id=$id", null)
              LeakTable.deleteByHeapAnalysisId(db, id)
            }
            AsyncTask.SERIAL_EXECUTOR.execute {
              all.forEach { (_, analysis) ->
                analysis.heapDumpFile.delete()
              }
            }
          }
    }
    notifyUpdateOnMainThread()
  }

  class Projection(
    val id: Long,
    val createdAtTimeMillis: Long,
    val leakCount: Int,
    val exceptionSummary: String?
  )

}