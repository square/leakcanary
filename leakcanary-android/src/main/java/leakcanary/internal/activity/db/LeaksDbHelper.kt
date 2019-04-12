package leakcanary.internal.activity.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class LeaksDbHelper(context: Context) : SQLiteOpenHelper(
    context, "leaks.db", null, 1
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
    TODO("Upgrade not needed yet")
  }
}