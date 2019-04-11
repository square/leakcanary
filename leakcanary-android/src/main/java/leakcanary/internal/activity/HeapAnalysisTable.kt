package leakcanary.internal.activity

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.AsyncTask
import leakcanary.CanaryLog
import leakcanary.HeapAnalysis
import leakcanary.HeapAnalysisFailure
import leakcanary.HeapAnalysisSuccess
import leakcanary.HeapDump
import leakcanary.Serializables
import leakcanary.internal.LeakCanaryUtils
import leakcanary.toByteArray
import org.intellij.lang.annotations.Language
import java.io.File

object HeapAnalysisTable {

  @Language("RoomSql")
  const val create = """CREATE TABLE heap_analysis
        (
        id INTEGER PRIMARY KEY,
        createdAtTimeMillis INTEGER,
        retainedInstanceCount INTEGER DEFAULT 0,
        exceptionSummary TEXT DEFAULT NULL,
        object BLOB
        )"""

  fun insert(
    db: SQLiteDatabase,
    heapAnalysis: HeapAnalysis
  ): Long {
    val values = ContentValues()
    values.put("object", heapAnalysis.toByteArray())
    values.put("createdAtTimeMillis", heapAnalysis.createdAtTimeMillis)
    when (heapAnalysis) {
      is HeapAnalysisSuccess -> {
        values.put("retainedInstanceCount", heapAnalysis.retainedInstances.size)
      }
      is HeapAnalysisFailure -> {
        val cause = heapAnalysis.exception.cause!!
        val exceptionSummary = "${cause.javaClass.simpleName} ${cause.message}"
        values.put("exceptionSummary", exceptionSummary)
      }
    }

    val id = db.insertOrThrow("heap_analysis", null, values)
    return id
  }

  fun <T : HeapAnalysis> retrieve(
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
          if (cursor.moveToNext())
            Serializables.fromByteArray(cursor.getBlob(0))
          else
            null
        }
  }

  fun retrieveAll(db: SQLiteDatabase): List<Summary> {
    return db.rawQuery(
        """
          SELECT
          id
          , createdAtTimeMillis
          , retainedInstanceCount
          , exceptionSummary
          FROM heap_analysis
          ORDER BY createdAtTimeMillis DESC
          """, null
    )
        .use { cursor ->
          val all = mutableListOf<Summary>()
          while (cursor.moveToNext()) {
            val summary = Summary(
                id = cursor.getLong(0),
                createdAtTimeMillis = cursor.getLong(1),
                retainedInstanceCount = cursor.getInt(2),
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
    heapDump: HeapDump
  ) {
    AsyncTask.SERIAL_EXECUTOR.execute {
      val heapDumpDeleted = heapDump.heapDumpFile.delete()
      if (!heapDumpDeleted) {
        CanaryLog.d("Could not delete heap dump file %s", heapDump.heapDumpFile.path)
      }
    }

    db.delete("heap_analysis", "id=$id", null)
  }

  fun deleteAll(
    db: SQLiteDatabase,
    context: Context
  ) {
    val leakDirectoryProvider = LeakCanaryUtils.getLeakDirectoryProvider(context)
    AsyncTask.SERIAL_EXECUTOR.execute { leakDirectoryProvider.clearLeakDirectory() }
    db.delete("heap_analysis", null, null)
  }

  class Summary(
    val id: Long,
    val createdAtTimeMillis: Long,
    val retainedInstanceCount: Int,
    val exceptionSummary: String?
  )

}