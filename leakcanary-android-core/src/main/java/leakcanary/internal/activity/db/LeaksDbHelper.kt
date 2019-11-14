package leakcanary.internal.activity.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class LeaksDbHelper(context: Context) : SQLiteOpenHelper(
    context, "leaks.db", null, VERSION
) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(HeapAnalysisTable.create)
    db.execSQL(LeakingInstanceTable.create)
    db.execSQL(LeakingInstanceTable.createGroupHashIndex)
  }

  override fun onUpgrade(
    db: SQLiteDatabase,
    oldVersion: Int,
    newVersion: Int
  ) {
    db.execSQL(HeapAnalysisTable.drop)
    db.execSQL(LeakingInstanceTable.drop)
    onCreate(db)
  }

  companion object {
    private const val VERSION = 17
  }
}