package leakcanary.internal.activity.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import leakcanary.internal.Serializables
import leakcanary.internal.toByteArray
import org.intellij.lang.annotations.Language
import shark.HeapAnalysisSuccess
import shark.Leak
import shark.LibraryLeak

internal object LeakTable {

  @Language("RoomSql")
  const val create = """
        CREATE TABLE leak
        (
        id INTEGER PRIMARY KEY,
        heap_analysis_id REFERENCES heap_analysis(id),
        group_hash TEXT,
        group_description TEXT,
        class_simple_name TEXT,
        is_library_leak INTEGER,
        object BLOB
        )"""

  @Language("RoomSql")
  const val createGroupHashIndex = """
        CREATE INDEX leak_group_hash
        on leak (group_hash)
    """

  @Language("RoomSql")
  const val drop = "DROP TABLE IF EXISTS leak"

  fun insert(
    db: SQLiteDatabase,
    heapAnalysisId: Long,
    leak: Leak
  ): Long {
    val values = ContentValues()
    values.put("heap_analysis_id", heapAnalysisId)
    values.put("group_hash", leak.groupHash)
    values.put("group_description", leak.createGroupDescription())
    values.put("class_simple_name", leak.classSimpleName)
    values.put("object", leak.toByteArray())
    values.put("is_library_leak", if (leak is LibraryLeak) 1 else 0)
    return db.insertOrThrow("leak", null, values)
  }

  class HeapAnalysisGroupProjection(
    val hash: String,
    val description: String,
    val createdAtTimeMillis: Long,
    val leakCount: Int,
    val isNew: Boolean,
    val isLibraryLeak: Boolean
  )

  fun retrieveHeapDumpLeaks(
    db: SQLiteDatabase,
    heapAnalysisId: Long
  ): Map<String, HeapAnalysisGroupProjection> {

    val isLatestHeapAnalysis = db.rawQuery("SELECT MAX(id) FROM heap_analysis", null)
        .use { cursor ->
          cursor.moveToNext()
          cursor.getLong(0) == heapAnalysisId
        }

    return db.rawQuery(
        """
          SELECT
          group_hash
          , group_description
          , MAX(created_at_time_millis) as created_at_time_millis
          , SUM(CASE WHEN heap_analysis_id=$heapAnalysisId THEN 1 ELSE 0 END) as leak_count
          , COUNT(*) as total_leak_count
          , MIN(is_library_leak) as is_library_leak
          FROM leak l
          LEFT JOIN heap_analysis h ON l.heap_analysis_id = h.id
          GROUP BY 1, 2
          HAVING leak_count > 0
          ORDER BY leak_count, created_at_time_millis DESC
          """, null
    )
        .use { cursor ->
          val projectionsByHash = linkedMapOf<String, HeapAnalysisGroupProjection>()
          while (cursor.moveToNext()) {
            val hash = cursor.getString(0)
            val description = cursor.getString(1)
            val createdAtTimeMillis = cursor.getLong(2)
            val leakCount = cursor.getInt(3)
            val totalLeakCount = cursor.getInt(4)
            val isNew = isLatestHeapAnalysis && leakCount == totalLeakCount
            val isLibraryLeak = cursor.getInt(5) == 1
            val group = HeapAnalysisGroupProjection(
                hash, description, createdAtTimeMillis, leakCount, isNew, isLibraryLeak
            )
            projectionsByHash[hash] = group
          }
          projectionsByHash
        }
  }

  class GroupProjection(
    val hash: String,
    val description: String,
    val createdAtTimeMillis: Long,
    val leakCount: Int
  )

  fun retrieveAllLeaks(
    db: SQLiteDatabase
  ): List<GroupProjection> {
    return db.rawQuery(
        """
          SELECT
          group_hash
          , group_description
          , MAX(created_at_time_millis) as created_at_time_millis
          , COUNT(*) as leak_count
          FROM leak l
          LEFT JOIN heap_analysis h ON l.heap_analysis_id = h.id
          GROUP BY 1, 2
          ORDER BY leak_count, created_at_time_millis DESC
          """, null
    )
        .use { cursor ->
          val all = mutableListOf<GroupProjection>()
          while (cursor.moveToNext()) {
            val group = GroupProjection(
                hash = cursor.getString(0),
                description = cursor.getString(1),
                createdAtTimeMillis = cursor.getLong(2),
                leakCount = cursor.getInt(3)
            )
            all.add(group)
          }
          all
        }
  }

  class LeakProjection(
    val id: Long,
    val analysisId: Long,
    val classSimpleName: String,
    val groupDescription: String,
    val createdAtTimeMillis: Long
  )

  fun retrieveLeaksByHash(
    db: SQLiteDatabase,
    groupHash: String
  ): List<LeakProjection> {
    return db.rawQuery(
        """
          SELECT
          l.id
          , l.heap_analysis_id
          , l.class_simple_name
          , l.group_description
          , h.created_at_time_millis
          FROM leak l
          LEFT JOIN heap_analysis h ON l.heap_analysis_id = h.id
          WHERE group_hash = ?
          """, arrayOf(groupHash)
    )
        .use { cursor ->
          val leaks = mutableListOf<LeakProjection>()
          while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val analysisId = cursor.getLong(1)
            val classSimpleName = cursor.getString(2)
            val groupDescription = cursor.getString(3)
            val createdAtTimeMillis = cursor.getLong(4)
            leaks += LeakProjection(
                id, analysisId, classSimpleName, groupDescription, createdAtTimeMillis
            )
          }
          return leaks
        }
  }

  class LeakDetails(
    val leak: Leak,
    val analysisId: Long,
    val analysis: HeapAnalysisSuccess
  )

  fun retrieveLeakById(
    db: SQLiteDatabase,
    leakId: Long
  ): LeakDetails? {
    return db.rawQuery(
        """
          SELECT
          l.heap_analysis_id
          , l.object
          , h.object
          FROM leak l
          LEFT JOIN heap_analysis h ON l.heap_analysis_id = h.id
          WHERE l.id = ?
          LIMIT 1
          """, arrayOf(leakId.toString())
    )
        .use { cursor ->
          return if (cursor.moveToNext()) {
            val heapAnalysisId = cursor.getLong(0)
            val leakingInstance = Serializables.fromByteArray<Leak>(cursor.getBlob(1))
            val analysis = Serializables.fromByteArray<HeapAnalysisSuccess>(cursor.getBlob(2))
            if (leakingInstance != null && analysis != null) {
              LeakDetails(leakingInstance, heapAnalysisId, analysis)
            } else null
          } else null
        }
  }

  fun deleteByHeapAnalysisId(
    db: SQLiteDatabase,
    heapAnalysisId: Long
  ) {
    db.delete("leak", "heap_analysis_id=$heapAnalysisId", null)
  }

  fun deleteAll(db: SQLiteDatabase) {
    db.delete("leak", null, null)
  }

  internal fun Leak.createGroupDescription(): String {
    return if (this is LibraryLeak) {
      "Library Leak: $pattern"
    } else {
      val leakCauses = leakTrace.leakCauses
      if (leakCauses.isEmpty()) {
        // Should rarely happen, don't expect to see 0 unknown and 100% leaking or 100% not leaking
        className
      } else {
        val element = leakCauses.first()
        val referenceName = element.reference!!.groupingName
        val refDescription = element.classSimpleName + "." + referenceName
        refDescription
      }
    }
  }
}