package leakcanary.internal.activity.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import leakcanary.Exclusion
import leakcanary.Exclusion.Status.WONT_FIX_LEAK
import leakcanary.LeakTrace
import leakcanary.LeakTraceElement
import leakcanary.LeakTraceElement.Type.ARRAY_ENTRY
import leakcanary.LeakingInstance
import leakcanary.Serializables
import leakcanary.internal.utils.to
import leakcanary.toByteArray
import org.intellij.lang.annotations.Language

internal object LeakingInstanceTable {

  @Language("RoomSql")
  const val create = """
        CREATE TABLE leaking_instance
        (
        id INTEGER PRIMARY KEY,
        heap_analysis_id REFERENCES heap_analysis(id),
        group_hash TEXT,
        group_description TEXT,
        class_simple_name TEXT,
        exclusion_status INTEGER,
        object BLOB
        )"""

  @Language("RoomSql")
  const val createGroupHashIndex = """
        CREATE INDEX leaking_instance_group_hash
        on leaking_instance (group_hash)
    """

  @Language("RoomSql")
  const val drop = "DROP TABLE IF EXISTS leaking_instance"

  fun insert(
    db: SQLiteDatabase,
    heapAnalysisId: Long,
    leakingInstance: LeakingInstance
  ): Long {
    val values = ContentValues()
    values.put("heap_analysis_id", heapAnalysisId)
    values.put("group_hash", leakingInstance.groupHash)
    values.put("group_description", leakingInstance.createGroupDescription())
    values.put("class_simple_name", leakingInstance.instanceClassSimpleName)
    values.put("object", leakingInstance.toByteArray())
    values.put("exclusion_status", leakingInstance.exclusionStatus?.ordinal ?: -1)
    return db.insertOrThrow("leaking_instance", null, values)
  }

  fun retrieve(
    db: SQLiteDatabase,
    id: Long
  ): Pair<Long, LeakingInstance>? {
    return db.rawQuery(
        """
          SELECT
          heap_analysis_id,
          object
          FROM leaking_instance
          WHERE id=$id
          """, null
    )
        .use { cursor ->
          if (cursor.moveToNext()) {
            val heapAnalysisId = cursor.getLong(0)
            val leakingInstance = Serializables.fromByteArray<LeakingInstance>(cursor.getBlob(1))
            if (leakingInstance == null) {
              null
            } else {
              heapAnalysisId to leakingInstance
            }
          } else
            null
        }
  }

  class HeapAnalysisGroupProjection(
    val hash: String,
    val description: String,
    val createdAtTimeMillis: Long,
    val leakCount: Int,
    val totalLeakCount: Int,
    val isNew: Boolean,
    val exclusionStatus: Exclusion.Status?
  )

  fun retrieveAllByHeapAnalysisId(
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
          , MIN(exclusion_status) as exclusion_status
          FROM leaking_instance l
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
            val exclusionStatusOrdinal = cursor.getInt(5)
            val exclusionStatus =
              if (exclusionStatusOrdinal == -1) null else Exclusion.Status.values()[exclusionStatusOrdinal]
            val group = HeapAnalysisGroupProjection(
                hash, description, createdAtTimeMillis, leakCount, totalLeakCount, isNew,
                exclusionStatus
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

  fun retrieveAllGroups(
    db: SQLiteDatabase
  ): List<GroupProjection> {
    return db.rawQuery(
        """
          SELECT
          group_hash
          , group_description
          , MAX(created_at_time_millis) as created_at_time_millis
          , COUNT(*) as leak_count
          FROM leaking_instance l
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

  class InstanceProjection(
    val id: Long,
    val classSimpleName: String,
    val createdAtTimeMillis: Long
  )

  fun retrieveGroup(
    db: SQLiteDatabase,
    groupHash: String
  ): Triple<LeakTrace, String, List<InstanceProjection>>? {
    val pair = db.rawQuery(
        """
           SELECT
            object
            , group_description
            FROM leaking_instance
            WHERE group_hash = ?
            LIMIT 1
            """, arrayOf(groupHash)
    )
        .use { cursor ->
          if (cursor.moveToNext()) {
            val leakingInstance = Serializables.fromByteArray<LeakingInstance>(cursor.getBlob(0))!!
            val leakTrace = leakingInstance.leakTrace

            val groupLeakTrace = if (leakingInstance.exclusionStatus == WONT_FIX_LEAK) {
              val index = leakTrace.elements.indexOfFirst { element -> element.exclusion != null }
              LeakTrace(
                  elements = listOf(leakTrace.elements[index].copy(labels = emptyList()))
              )
            } else {
              val elements = mutableListOf<LeakTraceElement>()
              for (index in 0 until leakTrace.elements.size) {
                if (leakTrace.elementMayBeLeakCause(index)) {
                  var element = leakTrace.elements[index]

                  val reference = element.reference!!
                  if (reference.type == ARRAY_ENTRY) {
                    // No array index in groups
                    element = element.copy(reference = reference.copy(name = "x"), labels = emptyList())
                  } else {
                    element = element.copy(labels = emptyList())
                  }

                  elements.add(element)
                }
              }
              LeakTrace(elements)
            }
            val groupDescription = cursor.getString(1)!!
            groupLeakTrace to groupDescription
          } else
            null
        } ?: return null

    val projections = db.rawQuery(
        """
         SELECT
          l.id
          , class_simple_name
          , h.created_at_time_millis
          FROM leaking_instance l
          LEFT JOIN heap_analysis h ON l.heap_analysis_id = h.id
          WHERE l.group_hash = ?
          ORDER BY h.created_at_time_millis DESC
          """, arrayOf(groupHash)
    )
        .use { cursor ->
          val projections = mutableListOf<InstanceProjection>()
          while (cursor.moveToNext()) {
            projections.add(
                InstanceProjection(
                    id = cursor.getLong(0),
                    classSimpleName = cursor.getString(1),
                    createdAtTimeMillis = cursor.getLong(2)
                )
            )
          }
          projections
        }

    return pair to projections
  }

  fun deleteByHeapAnalysisId(
    db: SQLiteDatabase,
    heapAnalysisId: Long
  ) {
    db.delete("leaking_instance", "heap_analysis_id=$heapAnalysisId", null)
  }

  fun deleteAll(db: SQLiteDatabase) {
    db.delete("leaking_instance", null, null)
  }

  private fun LeakingInstance.createGroupDescription(): String {
    return if (exclusionStatus == WONT_FIX_LEAK) {
      leakTrace.firstElementExclusion.matching
    } else {
      val leakCauses = leakTrace.leakCauses
      if (leakCauses.isEmpty()) {
        // Should rarely happen, don't expect to see 0 unknown and 100% leaking or 100% not leaking
        instanceClassName
      } else {
        val element = leakCauses.first()
        val referenceName = element.reference!!.groupingName
        val refDescription = element.classSimpleName + "." + referenceName
        refDescription
      }
    }
  }

}