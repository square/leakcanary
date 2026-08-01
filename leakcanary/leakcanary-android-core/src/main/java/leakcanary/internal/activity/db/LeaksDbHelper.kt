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
    db.execSQL(LeakTable.createSignatureIndex)
    db.execSQL(LeakTraceTable.create)
    db.execSQL(HeapDumpTable.create)
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
      db.execSQL("ALTER TABLE heap_analysis ADD COLUMN heap_dump_file_path TEXT DEFAULT NULL")
      db.execSQL(HeapDumpTable.create)
      // The retention cleanup now keeps heap dumps that no analysis was run on, so every analysis
      // stored before this version needs its heap dump file path filled in, or the heap dumps it
      // already analyzed would look like they're still waiting.
      val idToHeapDumpFilePath = db.rawQuery("SELECT id, object FROM heap_analysis", null)
        .use { cursor ->
          val paths = mutableListOf<Pair<Long, String>>()
          while (cursor.moveToNext()) {
            val analysis = Serializables.fromByteArray<HeapAnalysis>(cursor.getBlob(1))
            if (analysis != null) {
              paths += cursor.getLong(0) to analysis.heapDumpFile.absolutePath
            }
          }
          paths
        }
      db.inTransaction {
        idToHeapDumpFilePath.forEach { (id, heapDumpFilePath) ->
          val values = ContentValues()
          values.put("heap_dump_file_path", heapDumpFilePath)
          db.update("heap_analysis", values, "id=$id", null)
        }
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
    db.execSQL(HeapDumpTable.drop)
    onCreate(db)
  }

  companion object {
    internal const val VERSION = 26
    internal const val DATABASE_NAME = "leaks.db"
  }
}