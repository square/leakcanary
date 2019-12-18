package leakcanary

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.LeaksDbHelper
import org.junit.Test
import shark.HeapAnalysis
import shark.HeapAnalysisSuccess
import java.io.FileOutputStream
import kotlin.reflect.KClass

class DatabaseMigrationTest {

  @Test fun v19_upgraded_to_latest() {
    DB_V19 upgrade {
      version assertEquals LeaksDbHelper.VERSION
    }
  }

  @Test fun v19_has_2_heap_dumps() {
    DB_V19 upgrade {
      HeapAnalysisTable.retrieveAll(this).size assertEquals 2
    }
  }

  @Test fun v19_heap_dumps_can_be_deserialized() {
    DB_V19 upgrade {
      HeapAnalysisTable.retrieveAll(this)
          .forEach { projection ->
            val heapAnalysis = HeapAnalysisTable.retrieve<HeapAnalysis>(this, projection.id)!!
            heapAnalysis assertIs HeapAnalysisSuccess::class.java
          }
    }
  }

  @Test fun v19_has_3_leak_types() {
    DB_V19 upgrade {
      LeakTable.retrieveAllLeaks(this).size assertEquals 3
    }
  }

  @Test fun v19_leaks_are_not_new() {
    DB_V19 upgrade {
      LeakTable.retrieveAllLeaks(this)
          .forEach { leak ->
            leak.isNew assertEquals false
          }
    }
  }

  @Test fun v19_all_8_leaks_can_be_deserialized() {
    DB_V19 upgrade {
     val allLeaks = LeakTable.retrieveAllLeaks(this)
          .flatMap { leakTypeProjection ->
            LeakTable.retrieveLeaksByHash(this, leakTypeProjection.hash)
          }
          .map { leakProjection ->
            LeakTable.retrieveLeakById(this, leakProjection.id)
          }
      allLeaks.size assertEquals 8
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
          FileOutputStream(databaseFile).use { output ->
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
    const val DB_V19 = "leaks-v19-android-16.db"
  }

}