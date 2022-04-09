package leakcanary

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.LeaksDbHelper
import org.junit.Test
import shark.HeapAnalysis
import shark.HeapAnalysisSuccess
import shark.LeakTrace.GcRootType.JAVA_FRAME
import shark.LeakTrace.GcRootType.STICKY_CLASS

class DatabaseMigrationTest {

  @Test fun v24_upgraded_to_latest() {
    DB_V24 upgrade {
      version assertEquals LeaksDbHelper.VERSION
    }
  }

  @Test fun v24_has_1_heap_dumps() {
    DB_V24 upgrade {
      HeapAnalysisTable.retrieveAll(this).size assertEquals 1
    }
  }

  @Test fun v24_heap_dumps_can_be_deserialized() {
    DB_V24 upgrade {
      HeapAnalysisTable.retrieveAll(this)
        .forEach { projection ->
          val heapAnalysis = HeapAnalysisTable.retrieve<HeapAnalysis>(this, projection.id)!!
          heapAnalysis assertIs HeapAnalysisSuccess::class.java
        }
    }
  }

  @Test fun v24_has_8_leak_traces() {
    DB_V24 upgrade {
      val allLeakTraces = HeapAnalysisTable.retrieveAll(this)
        .map { HeapAnalysisTable.retrieve<HeapAnalysisSuccess>(this, it.id)!! }
        .flatMap { analysis ->
          analysis.allLeaks.toList()
        }
        .flatMap { leak ->
          leak.leakTraces
        }

      allLeakTraces.size assertEquals 8
    }
  }

  @Test fun v24_has_3_leak_types() {
    DB_V24 upgrade {
      LeakTable.retrieveAllLeaks(this).size assertEquals 3
    }
  }

  @Test fun v24_leaks_are_new() {
    DB_V24 upgrade {
      LeakTable.retrieveAllLeaks(this)
        .forEach { leak ->
          leak.isNew assertEquals true
        }
    }
  }

  @Test fun v24_has_5_sticky_class_and_3_java_frame_gc_roots() {
    DB_V24 upgrade {
      val allLeakTraces = HeapAnalysisTable.retrieveAll(this)
        .map { HeapAnalysisTable.retrieve<HeapAnalysisSuccess>(this, it.id)!! }
        .flatMap { analysis ->
          analysis.allLeaks.toList()
        }
        .flatMap { leak ->
          leak.leakTraces
        }
      val gcRootCounts = allLeakTraces.groupingBy { it.gcRootType }
        .eachCount()

      gcRootCounts.getValue(STICKY_CLASS) assertEquals 2
      gcRootCounts.getValue(JAVA_FRAME) assertEquals 3
    }
  }

  private infix fun String.upgrade(
    block: SQLiteDatabase.() -> Unit
  ) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext

    context.assets.open(this)
      .use { input ->
        val databaseFile = context.getDatabasePath(LeaksDbHelper.DATABASE_NAME)
        databaseFile.parentFile!!.mkdirs()
        databaseFile.outputStream().use { output ->
          input.copyTo(output)
        }
      }
    LeaksDbHelper(context).readableDatabase.use { db ->
      db.block()
    }
  }

  private infix fun Any.assertEquals(otherValue: Any) {
    if (this != otherValue) {
      throw AssertionError("Expecting <$this> to be equal to <$otherValue> but was not.")
    }
  }

  private infix fun Any.assertIs(javaClass: Class<out Any>) {
    if (!javaClass.isInstance(this)) {
      throw AssertionError(
        "Expecting <$this> to be an instance of <${javaClass.name}> but was <${this.javaClass.name}>."
      )
    }
  }

  companion object {
    const val DB_V24 = "leaks-v24.db"
  }
}
