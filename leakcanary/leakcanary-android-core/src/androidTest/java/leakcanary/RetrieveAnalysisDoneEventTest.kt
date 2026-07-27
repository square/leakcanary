package leakcanary

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import leakcanary.EventListener.Event.HeapAnalysisDone.HeapAnalysisFailed
import leakcanary.EventListener.Event.HeapAnalysisDone.HeapAnalysisSucceeded
import leakcanary.internal.AndroidDebugHeapAnalyzer
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.ScopedLeaksDb
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.HprofWriterHelper
import shark.OnAnalysisProgressListener
import shark.ValueHolder.IntHolder
import shark.dump
import org.junit.rules.TemporaryFolder

/**
 * [AndroidDebugHeapAnalyzer.retrieveAnalysisDoneEvent] is how the main process reads back the
 * result of an analysis that ran in the :leakcanary process, see
 * [leakcanary.internal.HeapAnalysisDoneDispatchWorker].
 */
internal class RetrieveAnalysisDoneEventTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @get:Rule
  var databaseRule = DatabaseRule()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Before fun installAppWatcher() {
    // AndroidDebugHeapAnalyzer reads InternalLeakCanary.application, which requires an install.
    if (!AppWatcher.isInstalled) {
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        AppWatcher.manualInstall(
          context.applicationContext as Application,
          watchersToInstall = emptyList()
        )
      }
    }
  }

  @Test fun retrieves_stored_success_as_HeapAnalysisSucceeded() {
    val analysis = analyzeHeapDump {
      "Holder" clazz {
        staticField["leak"] = "com.example.Leaking" watchedInstance {}
      }
    }
    val analysisId = insert(analysis)

    val event = AndroidDebugHeapAnalyzer.retrieveAnalysisDoneEvent("unique-id", analysisId)

    assertThat(event).isInstanceOf(HeapAnalysisSucceeded::class.java)
    val succeeded = event as HeapAnalysisSucceeded
    assertThat(succeeded.uniqueId).isEqualTo("unique-id")
    assertThat(succeeded.heapAnalysis.allLeaks.map { it.signature }.toList())
      .isEqualTo(analysis.allLeaks.map { it.signature }.toList())
  }

  @Test fun retrieves_stored_failure_as_HeapAnalysisFailed() {
    val failure = HeapAnalysisFailure(
      heapDumpFile = testFolder.newFile("failure.hprof"),
      createdAtTimeMillis = 42,
      analysisDurationMillis = 10,
      exception = HeapAnalysisException(RuntimeException("Boom"))
    )
    val analysisId = insert(failure)

    val event = AndroidDebugHeapAnalyzer.retrieveAnalysisDoneEvent("unique-id", analysisId)

    assertThat(event).isInstanceOf(HeapAnalysisFailed::class.java)
    val failed = event as HeapAnalysisFailed
    assertThat(failed.uniqueId).isEqualTo("unique-id")
    assertThat(failed.heapAnalysis.exception.cause).hasMessage("Boom")
  }

  @Test fun unread_leaks_are_reported_as_unread() {
    val analysis = analyzeHeapDump {
      "Holder" clazz {
        staticField["leak"] = "com.example.Leaking" watchedInstance {}
      }
    }
    val analysisId = insert(analysis)

    val event = AndroidDebugHeapAnalyzer.retrieveAnalysisDoneEvent(
      "unique-id", analysisId
    ) as HeapAnalysisSucceeded

    assertThat(event.unreadLeakSignatures)
      .isEqualTo(analysis.allLeaks.map { it.signature }.toSet())
  }

  @Test fun read_leaks_are_not_reported_as_unread() {
    val analysis = analyzeHeapDump {
      "Holder" clazz {
        staticField["leak"] = "com.example.Leaking" watchedInstance {}
      }
    }
    val analysisId = insert(analysis)
    ScopedLeaksDb.writableDatabase(context) { db ->
      analysis.allLeaks.forEach { LeakTable.markAsRead(db, it.signature) }
    }

    val event = AndroidDebugHeapAnalyzer.retrieveAnalysisDoneEvent(
      "unique-id", analysisId
    ) as HeapAnalysisSucceeded

    assertThat(event.unreadLeakSignatures).isEmpty()
  }

  @Test fun show_intent_points_at_the_stored_analysis() {
    val analysis = analyzeHeapDump {
      "Holder" clazz {
        staticField["leak"] = "com.example.Leaking" watchedInstance {}
      }
    }
    val analysisId = insert(analysis)

    val event = AndroidDebugHeapAnalyzer.retrieveAnalysisDoneEvent("unique-id", analysisId)!!

    assertThat(event.showIntent.getLongExtra("heapAnalysisId", -1)).isEqualTo(analysisId)
    assertThat(event.showIntent.getBooleanExtra("success", false)).isTrue()
  }

  @Test fun returns_null_when_the_analysis_is_gone() {
    val event = AndroidDebugHeapAnalyzer.retrieveAnalysisDoneEvent("unique-id", 12345)

    assertThat(event).isNull()
  }

  private fun insert(analysis: shark.HeapAnalysis): Long {
    return ScopedLeaksDb.writableDatabase(context) { db ->
      HeapAnalysisTable.insert(db, analysis)
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
}
