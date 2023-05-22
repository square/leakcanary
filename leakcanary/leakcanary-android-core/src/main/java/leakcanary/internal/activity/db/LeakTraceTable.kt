package leakcanary.internal.activity.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.intellij.lang.annotations.Language

internal object LeakTraceTable {

  @Language("RoomSql")
  const val create = """
        CREATE TABLE leak_trace
        (
        id INTEGER PRIMARY KEY,
        heap_analysis_id REFERENCES heap_analysis(id),
        leak_id REFERENCES leak(id),
        class_simple_name TEXT,
        leak_trace_index INTEGER
        )"""

  @Language("RoomSql")
  const val drop = "DROP TABLE IF EXISTS leak_trace"

  fun insert(
    db: SQLiteDatabase,
    leakId: Long,
    heapAnalysisId: Long,
    leakTraceIndex: Int,
    leakingObjectClassSimpleName: String
  ): Long {
    val values = ContentValues()
    values.put("heap_analysis_id", heapAnalysisId)
    values.put("leak_id", leakId)
    values.put("class_simple_name", leakingObjectClassSimpleName)
    values.put("leak_trace_index", leakTraceIndex)
    return db.insertOrThrow("leak_trace", null, values)
  }

  fun deleteByHeapAnalysisId(
    db: SQLiteDatabase,
    heapAnalysisId: Long
  ) {
    db.delete("leak_trace", "heap_analysis_id=$heapAnalysisId", null)
  }
}