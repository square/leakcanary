package leakcanary.internal

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import leakcanary.EventListener
import leakcanary.EventListener.Event.HeapAnalysisDone
import leakcanary.EventListener.Event.HeapAnalysisDone.HeapAnalysisFailed
import leakcanary.EventListener.Event.HeapAnalysisDone.HeapAnalysisSucceeded
import leakcanary.EventListener.Event.HeapAnalysisProgress
import leakcanary.EventListener.Event.HeapDump
import leakcanary.LeakCanary
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.HeapDumpDeletionTable
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.ScopedLeaksDb
import shark.CancelSignal
import shark.CanceledException
import shark.ConstantMemoryMetricsDualSourceProvider
import shark.FileSourceProvider
import shark.HeapAnalysis
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.HprofHeapGraph
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.OnAnalysisProgressListener
import shark.OnAnalysisProgressListener.Step.PARSING_HEAP_DUMP
import shark.OnAnalysisProgressListener.Step.REPORTING_HEAP_ANALYSIS
import shark.ProguardMappingReader
import shark.SharkLog

/**
 * This should likely turn into a public API, once there's a good answer for publishing progress:
 * cancellation is now [CancelSignal]'s job, but progress is still a listener called with one of ten
 * coarse steps, which isn't enough to show progress with.
 */
internal object AndroidDebugHeapAnalyzer {

  private const val PROGUARD_MAPPING_FILE_NAME = "leakCanaryObfuscationMapping.txt"

  private val application = InternalLeakCanary.application

  /**
   * Runs the heap analysis on the current thread, stores the result in the LeakCanary database and
   * returns the [EventListener.Event.HeapAnalysisDone] event for it. Callers are responsible for
   * dispatching that event.
   *
   * Throws [CanceledException] if [cancelSignal] stops the analysis, in which case nothing is stored
   * and there's no event to dispatch: an analysis that was asked to stop has no result to show.
   */
  @Throws(CanceledException::class)
  fun runAnalysisBlocking(
    heapDumped: HeapDump,
    cancelSignal: CancelSignal = CancelSignal.NEVER,
    progressEventListener: (HeapAnalysisProgress) -> Unit
  ): HeapAnalysisDone<*> {
    val progressListener = OnAnalysisProgressListener { step ->
      val percent = (step.ordinal * 1.0) / OnAnalysisProgressListener.Step.values().size
      progressEventListener(HeapAnalysisProgress(heapDumped.uniqueId, step, percent))
    }

    val heapDumpFile = heapDumped.file
    val heapDumpDurationMillis = heapDumped.durationMillis
    val heapDumpReason = heapDumped.reason

    val heapAnalysis = if (heapDumpFile.exists()) {
      analyzeHeap(heapDumpFile, progressListener, cancelSignal)
    } else {
      missingFileFailure(heapDumpFile)
    }

    val fullHeapAnalysis = when (heapAnalysis) {
      is HeapAnalysisSuccess -> heapAnalysis.copy(
        dumpDurationMillis = heapDumpDurationMillis,
        metadata = heapAnalysis.metadata + ("Heap dump reason" to heapDumpReason)
      )
      is HeapAnalysisFailure -> heapAnalysis
        .copy(dumpDurationMillis = heapDumpDurationMillis)
        .withOutOfMemoryGuidance(ProcessHeapLimit.read(application))
    }
    progressListener.onAnalysisProgress(REPORTING_HEAP_ANALYSIS)

    return ScopedLeaksDb.writableDatabase(application) { db ->
      val analysisId = HeapAnalysisTable.insert(db, fullHeapAnalysis)
      analysisDoneEvent(db, heapDumped.uniqueId, analysisId, fullHeapAnalysis)
    }
  }

  /**
   * Reads back the analysis stored under [analysisId] and rebuilds the [HeapAnalysisDone] event
   * for it. This is how the main process retrieves the result of an analysis that ran in the
   * :leakcanary process, see [HeapAnalysisDoneDispatchWorker].
   *
   * Returns null if that analysis is no longer in the database, e.g. because it was deleted from
   * the LeakCanary activity before the event could be dispatched.
   */
  fun retrieveAnalysisDoneEvent(
    uniqueId: String,
    analysisId: Long
  ): HeapAnalysisDone<*>? {
    return ScopedLeaksDb.writableDatabase(application) { db ->
      val heapAnalysis = HeapAnalysisTable.retrieve<HeapAnalysis>(db, analysisId)
      if (heapAnalysis == null) {
        SharkLog.d { "Heap analysis $analysisId not found in the LeakCanary database" }
        null
      } else {
        analysisDoneEvent(db, uniqueId, analysisId, heapAnalysis)
      }
    }
  }

  private fun analysisDoneEvent(
    db: SQLiteDatabase,
    uniqueId: String,
    analysisId: Long,
    heapAnalysis: HeapAnalysis
  ): HeapAnalysisDone<*> {
    return when (heapAnalysis) {
      is HeapAnalysisSuccess -> {
        val showIntent = LeakActivity.createSuccessIntent(application, analysisId)
        val leakSignatures = heapAnalysis.allLeaks.map { it.signature }.toSet()
        val leakSignatureStatuses = LeakTable.retrieveLeakReadStatuses(db, leakSignatures)
        val unreadLeakSignatures = leakSignatureStatuses.filter { (_, read) ->
          !read
        }.keys
          // keys returns LinkedHashMap$LinkedKeySet which isn't Serializable
          .toSet()
        HeapAnalysisSucceeded(
          uniqueId,
          analysisId,
          heapAnalysis,
          unreadLeakSignatures,
          showIntent
        )
      }
      is HeapAnalysisFailure -> {
        val showIntent = LeakActivity.createFailureIntent(application, analysisId)
        HeapAnalysisFailed(uniqueId, analysisId, heapAnalysis, showIntent)
      }
    }
  }

  private fun analyzeHeap(
    heapDumpFile: File,
    progressListener: OnAnalysisProgressListener,
    cancelSignal: CancelSignal
  ): HeapAnalysis {
    val config = LeakCanary.config
    val heapAnalyzer = HeapAnalyzer(progressListener)
    val proguardMappingReader = try {
      ProguardMappingReader(application.assets.open(PROGUARD_MAPPING_FILE_NAME))
    } catch (e: IOException) {
      null
    }

    progressListener.onAnalysisProgress(PARSING_HEAP_DUMP)

    val sourceProvider = ConstantMemoryMetricsDualSourceProvider(FileSourceProvider(heapDumpFile))

    val closeableGraph = try {
      sourceProvider.openHeapGraph(
        proguardMapping = proguardMappingReader?.readProguardMapping(),
        cancelSignal = cancelSignal
      )
    } catch (throwable: Throwable) {
      if (throwable is CanceledException) {
        throw throwable
      }
      return HeapAnalysisFailure(
        heapDumpFile = heapDumpFile,
        createdAtTimeMillis = System.currentTimeMillis(),
        analysisDurationMillis = 0,
        exception = HeapAnalysisException(throwable)
      )
    }
    return closeableGraph
      .use { graph ->
        val result = heapAnalyzer.analyze(
          heapDumpFile = heapDumpFile,
          graph = graph,
          leakingObjectFinder = config.leakingObjectFinder,
          referenceMatchers = config.referenceMatchers,
          computeRetainedHeapSize = config.computeRetainedHeapSize,
          objectInspectors = config.objectInspectors,
          metadataExtractor = config.metadataExtractor
        )
        if (result is HeapAnalysisSuccess) {
          val lruCacheStats = (graph as HprofHeapGraph).lruCacheStats()
          val randomAccessStats =
            "RandomAccess[" +
              "bytes=${sourceProvider.randomAccessByteReads}," +
              "reads=${sourceProvider.randomAccessReadCount}," +
              "size=${heapDumpFile.length()}" +
              "]"
          val stats = "$lruCacheStats $randomAccessStats"
          result.copy(metadata = result.metadata + ("Stats" to stats))
        } else result
      }
  }

  /**
   * The heap dump was there when the analysis was queued and isn't there now. LeakCanary records why
   * it deletes a heap dump file in the database, which outlives the process that deleted it, so this
   * can name the reason even when the analysis only runs after a restart. No record means LeakCanary
   * didn't delete it, and since heap dumps live in a directory only this app can reach, that leaves
   * the app's data being cleared or something else in the app removing the file.
   */
  private fun missingFileFailure(
    heapDumpFile: File
  ): HeapAnalysisFailure {
    val deletionReason = ScopedLeaksDb.readableDatabase(application) { db ->
      HeapDumpDeletionTable.retrieveReason(db, heapDumpFile)
    }
    val message = if (deletionReason != null) {
      "Hprof file $heapDumpFile missing. $deletionReason"
    } else {
      "Hprof file $heapDumpFile missing, and LeakCanary has no record of deleting it. That " +
        "directory is private to this app, so either the app's data was cleared or something " +
        "else in the app deleted the file."
    }
    val exception = IllegalStateException(message)
    return HeapAnalysisFailure(
      heapDumpFile = heapDumpFile,
      createdAtTimeMillis = System.currentTimeMillis(),
      analysisDurationMillis = 0,
      exception = HeapAnalysisException(exception)
    )
  }
}
