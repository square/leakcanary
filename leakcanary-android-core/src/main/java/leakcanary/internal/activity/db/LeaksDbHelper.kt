package leakcanary.internal.activity.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import leakcanary.internal.Serializables
import shark.HeapAnalysis
import shark.HeapAnalysisSuccess

internal class LeaksDbHelper(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, VERSION
) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(HeapAnalysisTable.create)
    db.execSQL(LeakTable.create)
    db.execSQL(LeakTable.createSignatureIndex)
    db.execSQL(LeakTraceTable.create)
  }

  override fun onUpgrade(
    db: SQLiteDatabase,
    oldVersion: Int,
    newVersion: Int
  ) {
    if (oldVersion < 19) {
      recreateDb(db)
    } else if (oldVersion == 19) {
      // Migration from LeakCanary 2.0
      val allAnalysis = db.rawQuery("SELECT object FROM heap_analysis", null)
          .use { cursor ->
            generateSequence {
              if (cursor.moveToNext()) {
                Serializables.fromByteArray<HeapAnalysis>(cursor.getBlob(0))
              } else {
                null
              }
            }.map {
              if (it is HeapAnalysisSuccess) {
                HeapAnalysisSuccess.upgradeFrom20Deserialized(it)
              } else it
            }.toList()
          }
      recreateDb(db)
      db.inTransaction {
        allAnalysis.forEach { HeapAnalysisTable.insert(db, it) }
        db.update("leak", ContentValues().apply {
          put("is_read", 1)
        }, null, null)
      }
    } else if (oldVersion == 23) {
      db.execSQL("ALTER TABLE heap_analysis ADD COLUMN dump_duration_millis INTEGER DEFAULT -1")
    } else {
      throw IllegalStateException("Database migration from $oldVersion not supported")
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
    db.execSQL(LeakTraceTable.drop)
    onCreate(db)
  }

  companion object {
    internal const val VERSION = 24
    internal const val DATABASE_NAME = "leaks.db"
  }
}