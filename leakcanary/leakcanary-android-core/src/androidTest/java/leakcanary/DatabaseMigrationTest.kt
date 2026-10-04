package leakcanary

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.LeaksDbHelper
import leakcanary.internal.activity.db.ScopedLeaksDb
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.HprofWriterHelper
import shark.OnAnalysisProgressListener
import shark.ValueHolder.IntHolder
import shark.dump

/**
 * Opening a `leaks.db` written by an older version of LeakCanary, against the committed
 * `leaks-v24.db` asset.
 *
 * **Every version below [LeaksDbHelper.VERSION] is dropped and recreated rather than migrated**, so
 * what there is to check is that the recreate leaves a database that works, not that any of the old
 * rows survive. Keeping a stored analysis stopped being possible when
 * `shark.LeakTraceObject.leakingStatus` became `verdict` and `LEAKING` / `NOT_LEAKING` became
 * `STUCK` / `EXPECTED`: Java serialization writes field names and enum constant names, so the
 * `heap_analysis` blobs an older version wrote name a property and three constants this one doesn't
 * declare.
 *
 * The risk of a recreate is a half dropped schema, which reads as an empty database and only fails
 * when something writes to it — so [v24_database_is_usable_after_being_recreated] inserts an
 * analysis and reads it back, and is the test that would catch it.
 */
class DatabaseMigrationTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun v24_upgraded_to_latest() {
    DB_V24 upgrade {
      assertThat(version).isEqualTo(LeaksDbHelper.VERSION)
    }
  }

  @Test fun v24_heap_analyses_are_gone() {
    DB_V24 upgrade {
      assertThat(HeapAnalysisTable.retrieveAll(this)).isEmpty()
    }
  }

  @Test fun v24_leaks_are_gone() {
    DB_V24 upgrade {
      assertThat(LeakTable.retrieveAllLeaks(this)).isEmpty()
    }
  }

  @Test fun v24_database_is_usable_after_being_recreated() {
    val analysis = analyzeHeapDump {
      "Holder" clazz {
        staticField["leak"] = "com.example.Leaking" watchedInstance {}
      }
    }

    DB_V24 upgrade {
      val analysisId = HeapAnalysisTable.insert(this, analysis)

      val retrieved = HeapAnalysisTable.retrieve<HeapAnalysisSuccess>(this, analysisId)!!
      assertThat(retrieved.allLeaks.map { it.leakFingerprint }.toList())
        .isEqualTo(analysis.allLeaks.map { it.leakFingerprint }.toList())
      assertThat(LeakTable.retrieveAllLeaks(this)).hasSize(analysis.allLeaks.count())
    }
  }

  private infix fun String.upgrade(
    block: SQLiteDatabase.() -> Unit
  ) {
    context.assets.open(this)
      .use { input ->
        val databaseFile = context.getDatabasePath(LeaksDbHelper.DATABASE_NAME)
        databaseFile.parentFile!!.mkdirs()
        databaseFile.outputStream().use { output ->
          input.copyTo(output)
        }
      }
    try {
      ScopedLeaksDb.writableDatabase(context) { db ->
        db.block()
      }
    } finally {
      context.deleteDatabase(LeaksDbHelper.DATABASE_NAME)
    }
  }

  private fun analyzeHeapDump(block: HprofWriterHelper.() -> Unit): HeapAnalysisSuccess {
    val hprofFile = writeHeapDump(block)
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    return heapAnalyzer.analyze(
      heapDumpFile = hprofFile,
      leakingObjectFinder = LeakCanary.config.leakingObjectFinder,
      referenceMatchers = LeakCanary.config.referenceMatchers,
      computeRetainedHeapSize = LeakCanary.config.computeRetainedHeapSize,
      objectInspectors = LeakCanary.config.objectInspectors,
      metadataExtractor = LeakCanary.config.metadataExtractor,
      proguardMapping = null
    ) as HeapAnalysisSuccess
  }

  private fun writeHeapDump(block: HprofWriterHelper.() -> Unit): File {
    val hprofFile = testFolder.newFile("temp.hprof")
    hprofFile.dump {
      "android.os.Build" clazz {
        staticField["MANUFACTURER"] = string("Samsing")
        staticField["ID"] = string("M4-rc20")
      }
      "android.os.Build\$VERSION" clazz {
        staticField["SDK_INT"] = IntHolder(47)
      }
      block()
    }
    return hprofFile
  }

  companion object {
    const val DB_V24 = "leaks-v24.db"
  }
}
