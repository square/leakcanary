package leakcanary.internal.activity

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LeaksDbHelper(context: Context) : SQLiteOpenHelper(
    context, "leaks.db", null, 1
) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(HeapAnalysisTable.create)
  }

  override fun onUpgrade(
    db: SQLiteDatabase,
    oldVersion: Int,
    newVersion: Int
  ) {
    TODO("Upgrade not needed yet")
  }
}