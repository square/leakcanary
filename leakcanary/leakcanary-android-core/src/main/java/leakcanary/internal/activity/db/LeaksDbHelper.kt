package leakcanary.internal.activity.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class LeaksDbHelper(context: Context) : SQLiteOpenHelper(
  context, DATABASE_NAME, null, VERSION
) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(HeapAnalysisTable.create)
    db.execSQL(LeakTable.create)
    db.execSQL(LeakTable.createLeakFingerprintIndex)
    db.execSQL(LeakTraceTable.create)
  }

  /**
   * Every version below [VERSION] is recreated rather than migrated, because
   * [shark.LeakTraceObject.leakingStatus] became `verdict` and `LEAKING` / `NOT_LEAKING` became
   * `STUCK` / `EXPECTED`. Java serialization writes both field names and enum constant names, so
   * every `heap_analysis` blob written before that is unreadable, and
   * [shark.LeakTraceObject]'s `serialVersionUID` now says so rather than letting one come back with
   * a null verdict on a non-null property. Rewriting them would mean mapping constants this version
   * no longer declares, for analyses of heap dumps that are long gone.
   */
  override fun onUpgrade(
    db: SQLiteDatabase,
    oldVersion: Int,
    newVersion: Int
  ) {
    recreateDb(db)
  }

  override fun onDowngrade(
    db: SQLiteDatabase,
    oldVersion: Int,
    newVersion: Int
  ) {
    recreateDb(db)
  }

  private fun recreateDb(db: SQLiteDatabase) {
    db.execSQL(HeapAnalysisTable.drop)
    db.execSQL(LeakTable.drop)
    db.execSQL(LeakTraceTable.drop)
    onCreate(db)
  }

  companion object {
    internal const val VERSION = 27
    internal const val DATABASE_NAME = "leaks.db"
  }
}
