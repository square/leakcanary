package leakcanary.internal

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
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.ScopedLeaksDb
import shark.ConstantMemoryMetricsDualSourceProvider
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
import shark.ThrowingCancelableFileSourceProvider

/**
 * This should likely turn into a public API but probably better to do once it's
 * coroutine based to supports cleaner cancellation + publishing progress.
 */
internal object AndroidDebugHeapAnalyzer {

  private const val PROGUARD_MAPPING_FILE_NAME = "leakCanaryObfuscationMapping.txt"

  private val application = InternalLeakCanary.application

  /**
   * Runs the heap analysis on the current thread and then sends a
   * [EventListener.Event.HeapAnalysisDone] event with the result (from the current thread as well).
   */
  fun runAnalysisBlocking(
    heapDumped: HeapDump,
    isCanceled: () -> Boolean = { false },
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
      analyzeHeap(heapDumpFile, progressListener, isCanceled)
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

    val analysisDoneEvent = ScopedLeaksDb.writableDatabase(application) { db ->
      val id = HeapAnalysisTable.insert(db, heapAnalysis)
      when (fullHeapAnalysis) {
        is HeapAnalysisSuccess -> {
          val showIntent = LeakActivity.createSuccessIntent(application, id)
          val leakSignatures = fullHeapAnalysis.allLeaks.map { it.signature }.toSet()
          val leakSignatureStatuses = LeakTable.retrieveLeakReadStatuses(db, leakSignatures)
          val unreadLeakSignatures = leakSignatureStatuses.filter { (_, read) ->
            !read
          }.keys
            // keys returns LinkedHashMap$LinkedKeySet which isn't Serializable
            .toSet()
          HeapAnalysisSucceeded(
            heapDumped.uniqueId,
            fullHeapAnalysis,
            unreadLeakSignatures,
            showIntent
          )
        }
        is HeapAnalysisFailure -> {
          val showIntent = LeakActivity.createFailureIntent(application, id)
          HeapAnalysisFailed(heapDumped.uniqueId, fullHeapAnalysis, showIntent)
        }
      }
    }
    LeakCanary.config.onHeapAnalyzedListener.onHeapAnalyzed(fullHeapAnalysis)
    return analysisDoneEvent
  }

  private fun analyzeHeap(
    heapDumpFile: File,
    progressListener: OnAnalysisProgressListener,
    isCanceled: () -> Boolean
  ): HeapAnalysis {
    val config = LeakCanary.config
    val heapAnalyzer = HeapAnalyzer(progressListener)
    val proguardMappingReader = try {
      ProguardMappingReader(application.assets.open(PROGUARD_MAPPING_FILE_NAME))
    } catch (e: IOException) {
      null
    }

    progressListener.onAnalysisProgress(PARSING_HEAP_DUMP)

    val sourceProvider =
      ConstantMemoryMetricsDualSourceProvider(ThrowingCancelableFileSourceProvider(heapDumpFile) {
        if (isCanceled()) {
          throw RuntimeException("Analysis canceled")
        }
      })

    val closeableGraph = try {
      sourceProvider.openHeapGraph(proguardMapping = proguardMappingReader?.readProguardMapping())
    } catch (throwable: Throwable) {
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
              "travel=${sourceProvider.randomAccessByteTravel}," +
              "range=${sourceProvider.byteTravelRange}," +
              "size=${heapDumpFile.length()}" +
              "]"
          val stats = "$lruCacheStats $randomAccessStats"
          result.copy(metadata = result.metadata + ("Stats" to stats))
        } else result
      }
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
