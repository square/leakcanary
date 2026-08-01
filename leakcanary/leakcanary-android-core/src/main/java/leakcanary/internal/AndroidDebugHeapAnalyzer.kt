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
import leakcanary.internal.activity.db.HeapDumpTable
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
   * Asking twice for the analysis of the same heap dump gives back the analysis that was already
   * stored rather than running a second one, and asking again for a heap dump whose analysis has
   * been cut short [MAX_ANALYSIS_ATTEMPTS] times stores a failure instead of trying once more.
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

    // LeakCanary dispatches the analysis of a heap dump again when it can't tell whether the first
    // dispatch is still alive, so this can be the 2nd time we're asked to analyze this heap dump.
    // Finding the analysis already stored is how that duplicate stops here instead of parsing the
    // heap dump again and storing a second copy of the same result.
    val (analysisAlreadyDone, analysisStartCount) = ScopedLeaksDb.writableDatabase(
      application
    ) { db ->
      val storedAnalysis = HeapAnalysisTable.retrieveIdByHeapDumpFilePath(db, heapDumpFile)
        ?.let { analysisId ->
          HeapAnalysisTable.retrieve<HeapAnalysis>(db, analysisId)?.let { heapAnalysis ->
            analysisDoneEvent(db, heapDumped.uniqueId, analysisId, heapAnalysis)
          }
        }
      if (storedAnalysis != null) {
        storedAnalysis to 0
      } else {
        null to HeapDumpTable.recordAnalysisStart(db, heapDumpFile)
      }
    }
    if (analysisAlreadyDone != null) {
      SharkLog.d { "Heap dump $heapDumpFile was already analyzed, not analyzing it again" }
      return analysisAlreadyDone
    }

    val heapAnalysis = if (analysisStartCount > MAX_ANALYSIS_ATTEMPTS) {
      abandonedFailure(heapDumpFile)
    } else if (heapDumpFile.exists()) {
      try {
        analyzeHeap(heapDumpFile, progressListener, cancelSignal)
      } catch (canceled: CanceledException) {
        // This analysis never got to fail, so it shouldn't count towards giving up on this heap dump.
        ScopedLeaksDb.writableDatabase(application) { db ->
          HeapDumpTable.recordAnalysisCanceled(db, heapDumpFile)
        }
        throw canceled
      }
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
      HeapDumpTable.retrieveDeletionReason(db, heapDumpFile)
    }
    val message = if (deletionReason != null) {
      "Hprof file $heapDumpFile missing. $deletionReason"
    } else {
      "Hprof file $heapDumpFile missing, and LeakCanary has no record of deleting it. That " +
        "directory is private to this app, so either the app's data was cleared or something " +
        "else in the app deleted the file."
    }
    return failure(heapDumpFile, IllegalStateException(message))
  }

  /**
   * [MAX_ANALYSIS_ATTEMPTS] analyses of this heap dump have started and none of them ever got to
   * store a result, which is what happens when parsing it kills the process: LeakCanary would
   * otherwise keep retrying it forever, and never dump the heap again while it waits. Storing this
   * failure is how it stops, and it's also what lets the retention cleanup delete that heap dump.
   * The file is left in place so it can still be shared from the LeakCanary UI, since a heap dump
   * that can't be analyzed is exactly the kind of thing worth attaching to a bug report.
   */
  private fun abandonedFailure(heapDumpFile: File): HeapAnalysisFailure {
    SharkLog.d {
      "Giving up on heap dump $heapDumpFile after $MAX_ANALYSIS_ATTEMPTS analysis attempts"
    }
    return failure(
      heapDumpFile, IllegalStateException(
        "LeakCanary gave up on analyzing this heap dump: $MAX_ANALYSIS_ATTEMPTS analyses of it " +
          "started and none ever finished, which is what happens when the process is killed while " +
          "parsing it. The heap dump file was left in place so you can still share it."
      )
    )
  }

  private fun failure(
    heapDumpFile: File,
    exception: Exception
  ) = HeapAnalysisFailure(
    heapDumpFile = heapDumpFile,
    createdAtTimeMillis = System.currentTimeMillis(),
    analysisDurationMillis = 0,
    exception = HeapAnalysisException(exception)
  )

  /**
   * How many analyses of the same heap dump may start before LeakCanary gives up on it. An analysis
   * that runs to the end stores a result, success or failure, so reaching this means every attempt
   * so far was cut short without one.
   */
  private const val MAX_ANALYSIS_ATTEMPTS = 3
}
