package leakcanary.internal.activity.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.AsyncTask
import leakcanary.internal.InternalLeakCanary
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

  @Language("RoomSql")
  const val create = """CREATE TABLE heap_analysis
        (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        created_at_time_millis INTEGER,
        leak_group_count INTEGER DEFAULT 0,
        exception_summary TEXT DEFAULT NULL,
        object BLOB
        )"""

  @Language("RoomSql")
  const val drop = "DROP TABLE IF EXISTS heap_analysis"

  fun insert(
    db: SQLiteDatabase,
    heapAnalysis: HeapAnalysis
  ): Long {
    val values = ContentValues()
    values.put("created_at_time_millis", heapAnalysis.createdAtTimeMillis)
    values.put("object", heapAnalysis.toByteArray())
    when (heapAnalysis) {
      is HeapAnalysisSuccess -> {
        val leakGroupCount = heapAnalysis.allLeaks.map { it.groupHash }.distinct().size
        values.put("leak_group_count", leakGroupCount)
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
    }
  }

  inline fun <reified T : HeapAnalysis> retrieve(
    db: SQLiteDatabase,
    id: Long
  ): T? {
    db.inTransaction {
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
          } ?: return null
    }
  }

  fun retrieveAll(db: SQLiteDatabase): List<Projection> {
    return db.rawQuery(
        """
          SELECT
          id
          , created_at_time_millis
          , leak_group_count
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
                leakGroupCount = cursor.getInt(2),
                exceptionSummary = cursor.getString(3)
            )
            all.add(summary)
          }
          all
        }
  }

  fun delete(
    db: SQLiteDatabase,
    id: Long,
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
      db.delete("heap_analysis", "id=$id", null)
      LeakTable.deleteByHeapAnalysisId(db, id)
    }
  }

  fun deleteAll(db: SQLiteDatabase) {
    val leakDirectoryProvider = InternalLeakCanary.leakDirectoryProvider
    AsyncTask.SERIAL_EXECUTOR.execute { leakDirectoryProvider.clearLeakDirectory() }
    db.inTransaction {
      db.delete("heap_analysis", null, null)
      LeakTable.deleteAll(db)
    }
  }

  class Projection(
    val id: Long,
    val createdAtTimeMillis: Long,
    val leakGroupCount: Int,
    val exceptionSummary: String?
  )

}