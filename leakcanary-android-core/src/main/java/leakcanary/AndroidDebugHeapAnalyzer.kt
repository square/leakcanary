package leakcanary

import java.io.File
import java.io.IOException
import leakcanary.EventListener.Event.HeapAnalysisDone.HeapAnalysisFailed
import leakcanary.EventListener.Event.HeapAnalysisDone.HeapAnalysisSucceeded
import leakcanary.EventListener.Event.HeapAnalysisProgress
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.LeakDirectoryProvider
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.LeaksDbHelper
import leakcanary.internal.activity.screen.HeapAnalysisFailureScreen
import leakcanary.internal.activity.screen.HeapDumpScreen
import leakcanary.internal.activity.screen.HeapDumpsScreen
import shark.HeapAnalysis
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.OnAnalysisProgressListener
import shark.OnAnalysisProgressListener.Step.REPORTING_HEAP_ANALYSIS
import shark.ProguardMappingReader

object AndroidDebugHeapAnalyzer {

  private const val PROGUARD_MAPPING_FILE_NAME = "leakCanaryObfuscationMapping.txt"

  private val application = InternalLeakCanary.application

  /**
   * Runs the heap analysis on the current thread and then sends a
   * [EventListener.Event.HeapAnalysisDone] event with the result (from the current thread as well).
   */
  fun runAnalysisBlocking(heapDumped: HeapDump) {
    val progressListener = OnAnalysisProgressListener { step ->
      val percent = (step.ordinal * 1.0) / OnAnalysisProgressListener.Step.values().size
      InternalLeakCanary.sendEvent(HeapAnalysisProgress(step, percent))
    }

    val heapDumpFile = heapDumped.file
    val heapDumpDurationMillis = heapDumped.durationMillis
    val heapDumpReason = heapDumped.reason

    val heapAnalysis = if (heapDumpFile.exists()) {
      analyzeHeap(heapDumpFile, progressListener)
    } else {
      missingFileFailure(heapDumpFile)
    }

    val fullHeapAnalysis = when (heapAnalysis) {
      is HeapAnalysisSuccess -> heapAnalysis.copy(
        dumpDurationMillis = heapDumpDurationMillis,
        metadata = heapAnalysis.metadata + ("Heap dump reason" to heapDumpReason)
      )
      is HeapAnalysisFailure -> {
        val failureCause = heapAnalysis.exception.cause!!
        if (failureCause is OutOfMemoryError) {
          heapAnalysis.copy(
            dumpDurationMillis = heapDumpDurationMillis,
            exception = HeapAnalysisException(
              RuntimeException(
                """
              Not enough memory to analyze heap. You can:
              - Kill the app then restart the analysis from the LeakCanary activity.
              - Increase the memory available to your debug app with largeHeap=true: https://developer.android.com/guide/topics/manifest/application-element#largeHeap
              - Set up LeakCanary to run in a separate process: https://square.github.io/leakcanary/recipes/#running-the-leakcanary-analysis-in-a-separate-process
              - Download the heap dump from the LeakCanary activity then run the analysis from your computer with shark-cli: https://square.github.io/leakcanary/shark/#shark-cli
            """.trimIndent(), failureCause
              )
            )
          )
        } else {
          heapAnalysis.copy(dumpDurationMillis = heapDumpDurationMillis)
        }
      }
    }
    progressListener.onAnalysisProgress(REPORTING_HEAP_ANALYSIS)

    val db = LeaksDbHelper(application).writableDatabase
    val id = HeapAnalysisTable.insert(db, heapAnalysis)

    when (fullHeapAnalysis) {
      is HeapAnalysisSuccess -> {
        val screenToShow = HeapDumpScreen(id)
        val showIntent = LeakActivity.createPendingIntent(
          application, arrayListOf(HeapDumpsScreen(), screenToShow)
        )
        val leakSignatures = fullHeapAnalysis.allLeaks.map { it.signature }.toSet()
        val leakSignatureStatuses = LeakTable.retrieveLeakReadStatuses(db, leakSignatures)
        val unreadLeakSignatures = leakSignatureStatuses.filter { (_, read) ->
          !read
        }.keys
        InternalLeakCanary.sendEvent(
          HeapAnalysisSucceeded(
            fullHeapAnalysis,
            unreadLeakSignatures,
            showIntent
          )
        )
      }
      is HeapAnalysisFailure -> {
        val screenToShow = HeapAnalysisFailureScreen(id)
        val showIntent = LeakActivity.createPendingIntent(
          application, arrayListOf(HeapDumpsScreen(), screenToShow)
        )
        InternalLeakCanary.sendEvent(HeapAnalysisFailed(fullHeapAnalysis, showIntent))
      }
    }
    // Can't leverage .use{} because close() was added in API 16 and we're min SDK 14.
    db.releaseReference()
    LeakCanary.config.onHeapAnalyzedListener.onHeapAnalyzed(fullHeapAnalysis)
  }

  private fun analyzeHeap(
    heapDumpFile: File,
    progressListener: OnAnalysisProgressListener
  ): HeapAnalysis {
    val config = LeakCanary.config
    val heapAnalyzer = HeapAnalyzer(progressListener)
    val proguardMappingReader = try {
      ProguardMappingReader(application.assets.open(PROGUARD_MAPPING_FILE_NAME))
    } catch (e: IOException) {
      null
    }
    return heapAnalyzer.analyze(
      heapDumpFile = heapDumpFile,
      leakingObjectFinder = config.leakingObjectFinder,
      referenceMatchers = config.referenceMatchers,
      computeRetainedHeapSize = config.computeRetainedHeapSize,
      objectInspectors = config.objectInspectors,
      metadataExtractor = config.metadataExtractor,
      proguardMapping = proguardMappingReader?.readProguardMapping()
    )
  }

  private fun missingFileFailure(
    heapDumpFile: File
  ): HeapAnalysisFailure {
    val deletedReason = LeakDirectoryProvider.hprofDeleteReason(heapDumpFile)
    val exception = IllegalStateException(
      "Hprof file $heapDumpFile missing, deleted because: $deletedReason"
    )
    return HeapAnalysisFailure(
      heapDumpFile = heapDumpFile,
      createdAtTimeMillis = System.currentTimeMillis(),
      analysisDurationMillis = 0,
      exception = HeapAnalysisException(exception)
    )
  }
}
