package leakcanary.internal.activity.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class LeaksDbHelper(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, VERSION
) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(HeapAnalysisTable.create)
    db.execSQL(LeakTable.create)
    db.execSQL(LeakTable.createGroupHashIndex)
  }

  override fun onUpgrade(
    db: SQLiteDatabase,
    oldVersion: Int,
    newVersion: Int
  ) {
    // LeakCanary 2.0 to LeakCanary 2.1
    if (oldVersion == 19 && newVersion == 21) {
      db.execSQL(
          """
        ALTER TABLE leak
          ADD is_read INTEGER;
      """.trimIndent()
      )
      db.update("leak", ContentValues().apply {
        put("is_read", 1)
      }, null, null)
    } else {
      recreateDb(db)
    }
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
    onCreate(db)
  }

  companion object {
    internal const val VERSION = 21
    internal const val DATABASE_NAME = "leaks.db"
  }
}