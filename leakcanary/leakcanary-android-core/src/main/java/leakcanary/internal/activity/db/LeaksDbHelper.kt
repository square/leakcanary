package leakcanary.internal.activity.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import leakcanary.internal.Serializables
import leakcanary.internal.toByteArray
import shark.HeapAnalysis
import shark.HeapAnalysisSuccess
import shark.LeakTrace

internal class LeaksDbHelper(context: Context) : SQLiteOpenHelper(
  context, DATABASE_NAME, null, VERSION
) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(HeapAnalysisTable.create)
    db.execSQL(LeakTable.create)
    db.execSQL(LeakTable.createLeakFingerprintIndex)
    db.execSQL(LeakTraceTable.create)
  }

  override fun onUpgrade(
    db: SQLiteDatabase,
    oldVersion: Int,
    newVersion: Int
  ) {
    if (oldVersion < 23) {
      recreateDb(db)
      return
    }
    if (oldVersion < 24) {
      db.execSQL("ALTER TABLE heap_analysis ADD COLUMN dump_duration_millis INTEGER DEFAULT -1")
    }
    if (oldVersion < 25) {
      // Fix owningClassName=null in the serialized heap analysis.
      // https://github.com/square/leakcanary/issues/2067
      val idToAnalysis = db.rawQuery("SELECT id, object FROM heap_analysis", null)
        .use { cursor ->
          generateSequence {
            if (cursor.moveToNext()) {
              val id = cursor.getLong(0)
              val analysis = Serializables.fromByteArray<HeapAnalysis>(cursor.getBlob(1))
              id to analysis
            } else {
              null
            }
          }
            .filter {
              it.second is HeapAnalysisSuccess
            }
            .map { pair ->
              val analysis = pair.second as HeapAnalysisSuccess

              val unreachableObjects = try {
                analysis.unreachableObjects
              } catch (ignored: NullPointerException) {
                // This currently doesn't trigger but the Kotlin compiler might change one day.
                emptyList()
              } ?: emptyList() // Compiler doesn't know it but runtime can have null.
              pair.first to analysis.copy(
                unreachableObjects = unreachableObjects,
                applicationLeaks = analysis.applicationLeaks.map { leak ->
                  leak.copy(leak.leakTraces.fixNullReferenceOwningClassName())
                },
                libraryLeaks = analysis.libraryLeaks.map { leak ->
                  leak.copy(leak.leakTraces.fixNullReferenceOwningClassName())
                }
              )
            }.toList()
        }
      db.inTransaction {
        idToAnalysis.forEach { (id, heapAnalysis) ->
          val values = ContentValues()
          values.put("object", heapAnalysis.toByteArray())
          db.update("heap_analysis", values, "id=$id", null)
        }
      }
    }
    if (oldVersion < 26) {
      // The leak.signature column is now leak.fingerprint. ALTER TABLE RENAME COLUMN needs SQLite
      // 3.25, i.e. API 30, so the table is copied over instead: the procedure SQLite documents for
      // changing a schema, which is create, copy, drop, rename. leak.id is preserved because
      // leak_trace.leak_id points to it.
      db.inTransaction {
        db.execSQL("DROP INDEX IF EXISTS leak_signature")
        db.execSQL(
          """
          CREATE TABLE leak_new
          (
          id INTEGER PRIMARY KEY,
          fingerprint TEXT UNIQUE,
          short_description TEXT,
          is_library_leak INTEGER,
          is_read INTEGER
          )"""
        )
        db.execSQL(
          """
          INSERT INTO leak_new (id, fingerprint, short_description, is_library_leak, is_read)
          SELECT id, signature, short_description, is_library_leak, is_read
          FROM leak"""
        )
        db.execSQL(LeakTable.drop)
        db.execSQL("ALTER TABLE leak_new RENAME TO leak")
        db.execSQL(LeakTable.createLeakFingerprintIndex)
      }
    }
  }

  private fun List<LeakTrace>.fixNullReferenceOwningClassName(): List<LeakTrace> {
    return map { leakTrace ->
      leakTrace.copy(
        referencePath = leakTrace.referencePath.map { reference ->
          val owningClassName = try {
            // This can return null at runtime from previous serialized version without the field.
            reference.owningClassName
          } catch (ignored: NullPointerException) {
            // This currently doesn't trigger but the Kotlin compiler might change one day.
            null
          }
          if (owningClassName == null) {
            reference.copy(owningClassName = reference.originObject.classSimpleName)
          } else {
            reference
          }
        })
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
    internal const val VERSION = 26
    internal const val DATABASE_NAME = "leaks.db"
  }
}