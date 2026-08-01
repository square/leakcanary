package leakcanary.internal.activity.db

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.intellij.lang.annotations.Language

/**
 * What LeakCanary knows about a heap dump file besides the analysis it eventually produces: how many
 * analyses of it have started, and why LeakCanary deleted it.
 *
 * Both facts have to outlive the process that recorded them. An analysis is dispatched to
 * WorkManager or to a background thread and can run minutes or days later, in a process that knows
 * nothing about the one that dumped the heap: the file on disk, the analyses in [HeapAnalysisTable]
 * and this table are all it has to go on.
 */
internal object HeapDumpTable {

  @Language("RoomSql")
  const val create = """CREATE TABLE heap_dump
        (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        file_path TEXT UNIQUE,
        analysis_start_count INTEGER DEFAULT 0,
        deletion_reason TEXT DEFAULT NULL
        )"""

  @Language("RoomSql")
  const val drop = "DROP TABLE IF EXISTS heap_dump"

  /**
   * Records that an analysis of [heapDumpFile] is starting and returns how many have now started,
   * this one included. An analysis that finishes stores its result in [HeapAnalysisTable], so a
   * count that keeps growing with nothing stored means every attempt so far was cut short, e.g.
   * because parsing that heap dump gets the process killed.
   */
  fun recordAnalysisStart(
    db: SQLiteDatabase,
    heapDumpFile: File
  ): Int {
    val path = heapDumpFile.absolutePath
    return db.inTransaction {
      insertPathIfAbsent(path)
      execSQL(
        "UPDATE heap_dump SET analysis_start_count = analysis_start_count + 1 WHERE file_path=?",
        arrayOf(path)
      )
      rawQuery("SELECT analysis_start_count FROM heap_dump WHERE file_path=?", arrayOf(path))
        .use { cursor ->
          cursor.moveToNext()
          cursor.getInt(0)
        }
    }
  }

  /**
   * Undoes the [recordAnalysisStart] of an analysis that was asked to stop. Being canceled isn't a
   * failed attempt: the analysis never got the chance to fail, and counting it would eventually make
   * LeakCanary give up on a heap dump it never really tried to read.
   */
  fun recordAnalysisCanceled(
    db: SQLiteDatabase,
    heapDumpFile: File
  ) {
    db.execSQL(
      "UPDATE heap_dump SET analysis_start_count = MAX(0, analysis_start_count - 1) " +
        "WHERE file_path=?",
      arrayOf(heapDumpFile.absolutePath)
    )
  }

  /**
   * Records that LeakCanary deleted [heapDumpFile] for [reason], so that an analysis that reads that
   * file after the fact can say what happened to it.
   */
  fun recordDeletion(
    db: SQLiteDatabase,
    heapDumpFile: File,
    reason: String
  ) {
    val path = heapDumpFile.absolutePath
    db.inTransaction {
      insertPathIfAbsent(path)
      execSQL("UPDATE heap_dump SET deletion_reason=? WHERE file_path=?", arrayOf(reason, path))
    }
  }

  /**
   * The reason [heapDumpFile] was deleted, or null if LeakCanary has no record of deleting it.
   */
  fun retrieveDeletionReason(
    db: SQLiteDatabase,
    heapDumpFile: File
  ): String? {
    return db.rawQuery(
      "SELECT deletion_reason FROM heap_dump WHERE file_path=?",
      arrayOf(heapDumpFile.absolutePath)
    )
      .use { cursor ->
        if (cursor.moveToNext()) cursor.getString(0) else null
      }
  }

  /**
   * Adds a row for [path] if this is the first thing LeakCanary records about that heap dump, then
   * drops all but the [MAX_STORED_HEAP_DUMPS] most recent rows: a heap dump that far back is long
   * gone, and so is any analysis that was waiting for it, so keeping its row would grow the table
   * for nobody.
   */
  private fun SQLiteDatabase.insertPathIfAbsent(path: String) {
    execSQL("INSERT OR IGNORE INTO heap_dump (file_path) VALUES (?)", arrayOf(path))
    execSQL(
      """
      DELETE FROM heap_dump
      WHERE id NOT IN (
      SELECT id
      FROM heap_dump
      ORDER BY id DESC
      LIMIT $MAX_STORED_HEAP_DUMPS
      )
      """
    )
  }

  private const val MAX_STORED_HEAP_DUMPS = 100
}
