package leakcanary.internal.activity.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.intellij.lang.annotations.Language

/**
 * Records why LeakCanary deleted a heap dump file, so that an analysis reading that file after the
 * fact can say what happened to it.
 *
 * This has to be durable: an analysis is queued on WorkManager, which survives process death and
 * reboots, so the analysis that finds the file gone usually runs in a later process than the one
 * that deleted it.
 */
internal object HeapDumpDeletionTable {

  @Language("RoomSql")
  const val create = """CREATE TABLE heap_dump_deletion
        (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        heap_dump_file_path TEXT,
        reason TEXT
        )"""

  @Language("RoomSql")
  const val drop = "DROP TABLE IF EXISTS heap_dump_deletion"

  fun insert(
    db: SQLiteDatabase,
    heapDumpFile: File,
    reason: String
  ) {
    val values = ContentValues()
    values.put("heap_dump_file_path", heapDumpFile.absolutePath)
    values.put("reason", reason)
    db.inTransaction {
      insertOrThrow("heap_dump_deletion", null, values)
      // An analysis queued behind this many heap dumps is never going to run, so keeping its
      // deletion around forever would grow the table for nobody.
      execSQL(
        """
        DELETE FROM heap_dump_deletion
        WHERE id NOT IN (
        SELECT id
        FROM heap_dump_deletion
        ORDER BY id DESC
        LIMIT $MAX_STORED_DELETIONS
        )
        """
      )
    }
  }

  /**
   * The reason [heapDumpFile] was deleted, or null if LeakCanary has no record of deleting it.
   */
  fun retrieveReason(
    db: SQLiteDatabase,
    heapDumpFile: File
  ): String? {
    return db.rawQuery(
      """
          SELECT
          reason
          FROM heap_dump_deletion
          WHERE heap_dump_file_path=?
          ORDER BY id DESC
          LIMIT 1
          """, arrayOf(heapDumpFile.absolutePath)
    )
      .use { cursor ->
        if (cursor.moveToNext()) cursor.getString(0) else null
      }
  }

  private const val MAX_STORED_DELETIONS = 100
}
