package leakcanary.internal.activity.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.intellij.lang.annotations.Language
import shark.Leak
import shark.LibraryLeak

internal object LeakTable {

  @Language("RoomSql")
  const val create = """
        CREATE TABLE leak
        (
        id INTEGER PRIMARY KEY,
        signature TEXT UNIQUE,
        short_description TEXT,
        is_library_leak INTEGER,
        is_read INTEGER
        )"""

  @Language("RoomSql")
  const val createSignatureIndex = """
        CREATE INDEX leak_signature
        on leak (signature)
    """

  @Language("RoomSql")
  const val drop = "DROP TABLE IF EXISTS leak"

  fun insert(
    db: SQLiteDatabase,
    heapAnalysisId: Long,
    leak: Leak
  ): Long {
    val values = ContentValues()
    values.put("signature", leak.signature)
    values.put("short_description", leak.shortDescription)
    values.put("is_library_leak", if (leak is LibraryLeak) 1 else 0)
    values.put("is_read", 0)

    db.insertWithOnConflict("leak", null, values, SQLiteDatabase.CONFLICT_IGNORE)

    val leakId =
      db.rawQuery("SELECT id from leak WHERE signature = '${leak.signature}' LIMIT 1", null)
          .use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else throw IllegalStateException(
                "No id found for leak with signature '${leak.signature}'"
            )
          }

    leak.leakTraces.forEachIndexed { index, leakTrace ->
      LeakTraceTable.insert(
          db = db,
          leakId = leakId,
          heapAnalysisId = heapAnalysisId,
          leakTraceIndex = index,
          leakingObjectClassSimpleName = leakTrace.leakingObject.classSimpleName
      )
    }

    return leakId
  }

  fun retrieveLeakReadStatuses(
    db: SQLiteDatabase,
    signatures: Set<String>
  ): Map<String, Boolean> {
    return db.rawQuery(
        """
      SELECT
      signature
      , is_read
      FROM leak
      WHERE signature IN (${signatures.joinToString { "'$it'" }})
    """, null
    )
        .use { cursor ->
          val leakReadStatuses = mutableMapOf<String, Boolean>()
          while (cursor.moveToNext()) {
            val signature = cursor.getString(0)
            val isRead = cursor.getInt(1) == 1
            leakReadStatuses[signature] = isRead
          }
          leakReadStatuses
        }
  }

  class AllLeaksProjection(
    val signature: String,
    val shortDescription: String,
    val createdAtTimeMillis: Long,
    val leakTraceCount: Int,
    val isLibraryLeak: Boolean,
    val isNew: Boolean
  )

  fun retrieveAllLeaks(
    db: SQLiteDatabase
  ): List<AllLeaksProjection> {
    return db.rawQuery(
        """
          SELECT
          l.signature
          , MIN(l.short_description)
          , MAX(h.created_at_time_millis) as created_at_time_millis
          , COUNT(*) as leak_trace_count
          , MIN(l.is_library_leak) as is_library_leak
          , MAX(l.is_read) as is_read
          FROM leak_trace lt
          LEFT JOIN leak l on lt.leak_id = l.id
          LEFT JOIN heap_analysis h ON lt.heap_analysis_id = h.id
          GROUP BY 1
          ORDER BY leak_trace_count DESC, created_at_time_millis DESC
          """, null
    )
        .use { cursor ->
          val all = mutableListOf<AllLeaksProjection>()
          while (cursor.moveToNext()) {
            val group = AllLeaksProjection(
                signature = cursor.getString(0),
                shortDescription = cursor.getString(1),
                createdAtTimeMillis = cursor.getLong(2),
                leakTraceCount = cursor.getInt(3),
                isLibraryLeak = cursor.getInt(4) == 1,
                isNew = cursor.getInt(5) == 0
            )
            all.add(group)
          }
          all
        }
  }

  fun markAsRead(
    db: SQLiteDatabase,
    signature: String
  ) {
    val values = ContentValues().apply { put("is_read", 1) }
    db.update("leak", values, "signature = ?", arrayOf(signature))
  }

  class LeakProjection(
    val shortDescription: String,
    val isNew: Boolean,
    val isLibraryLeak: Boolean,
    val leakTraces: List<LeakTraceProjection>
  )

  class LeakTraceProjection(
    val leakTraceIndex: Int,
    val heapAnalysisId: Long,
    val classSimpleName: String,
    val createdAtTimeMillis: Long
  )

  fun retrieveLeakBySignature(
    db: SQLiteDatabase,
    signature: String
  ): LeakProjection? {
    return db.rawQuery(
        """
          SELECT
          lt.leak_trace_index
          , lt.heap_analysis_id
          , lt.class_simple_name
          , h.created_at_time_millis
          , l.short_description
          , l.is_read
          , l.is_library_leak
          FROM leak_trace lt
          LEFT JOIN leak l on lt.leak_id = l.id
          LEFT JOIN heap_analysis h ON lt.heap_analysis_id = h.id
          WHERE l.signature = ?
          ORDER BY h.created_at_time_millis DESC
          """, arrayOf(signature)
    )
        .use { cursor ->
          return if (cursor.moveToFirst()) {
            val leakTraces = mutableListOf<LeakTraceProjection>()
            val leakProjection = LeakProjection(
                shortDescription = cursor.getString(4),
                isNew = cursor.getInt(5) == 0,
                isLibraryLeak = cursor.getInt(6) == 1,
                leakTraces = leakTraces
            )
            leakTraces.addAll(generateSequence(cursor, {
              if (cursor.moveToNext()) cursor else null
            }).map {
              LeakTraceProjection(
                  leakTraceIndex = cursor.getInt(0),
                  heapAnalysisId = cursor.getLong(1),
                  classSimpleName = cursor.getString(2),
                  createdAtTimeMillis = cursor.getLong(3)
              )
            })
            leakProjection
          } else {
            null
          }
        }
  }

  fun deleteByHeapAnalysisId(
    db: SQLiteDatabase,
    heapAnalysisId: Long
  ) {
    LeakTraceTable.deleteByHeapAnalysisId(db, heapAnalysisId)
    db.execSQL(
        """
      DELETE
      FROM leak
      WHERE NOT EXISTS (
      SELECT *
      FROM leak_trace lt
      WHERE leak.id = lt.leak_id)
    """
    )
  }
}