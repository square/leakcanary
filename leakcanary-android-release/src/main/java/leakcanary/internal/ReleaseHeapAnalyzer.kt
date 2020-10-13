package leakcanary.internal

import android.app.Application
import android.os.Debug
import android.os.SystemClock
import okio.buffer
import okio.sink
import shark.ConstantMemoryMetricsDualSourceProvider
import shark.HeapAnalysis
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.HprofHeapGraph
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofPrimitiveArrayStripper
import shark.LeakingObjectFinder
import shark.MetadataExtractor
import shark.ObjectInspector
import shark.OnAnalysisProgressListener
import shark.ReferenceMatcher
import shark.SharkLog
import java.io.File
import java.util.UUID

internal class ReleaseHeapAnalyzer(
  private val application: Application,
  private val leakingObjectFinder: LeakingObjectFinder,
  private val referenceMatchers: List<ReferenceMatcher>,
  private val computeRetainedHeapSize: Boolean = false,
  private val objectInspectors: List<ObjectInspector>,
  private val metadataExtractor: MetadataExtractor
) {

  @Volatile
  private var stopAnalysing = false

  private val handler = startBackgroundHandlerThread("heap-analyzer")

  fun start() {
    handler.removeCallbacksAndMessages(null)
    handler.post {
      stopAnalysing = false
      val result = analyzeHeap()
      result.heapDumpFile.delete()
      dispatchAnalysisResult(result)
    }
  }

  fun stop() {
    stopAnalysing = true
    handler.removeCallbacksAndMessages(null)
  }

  // TODO Remove all hprof files while analysing and also on app startup.

  private fun analyzeHeap(): HeapAnalysis {
    val fileNamePrefix = "heap-${UUID.randomUUID()}"
    val filesDir = application.filesDir!!
    val sensitiveHeapDumpFile = File(filesDir, "$fileNamePrefix.hprof")
    // Any call to System.exit(0) will run shutdown hooks that will attempt to remove this
    // file. Note that this is best effort, and won't delete if the VM is killed by the system.
    sensitiveHeapDumpFile.deleteOnExit()
    val heapDumpUptimeMillis = SystemClock.uptimeMillis()
    // TODO Set this via reflection if available in classpath.
    // KeyedWeakReference.heapDumpUptimeMillis = heapDumpUptimeMillis

    try {
      Debug.dumpHprofData(sensitiveHeapDumpFile.absolutePath)
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not dump heap" }
      return HeapAnalysisFailure(
          heapDumpFile = sensitiveHeapDumpFile,
          createdAtTimeMillis = System.currentTimeMillis(),
          dumpDurationMillis = SystemClock.uptimeMillis() - heapDumpUptimeMillis,
          analysisDurationMillis = 0,
          exception = HeapAnalysisException(throwable)
      )
    }

    val analysisStarted = SystemClock.uptimeMillis()
    val heapDumpDurationMillis = analysisStarted - heapDumpUptimeMillis

    val strippedHeapDumpFile = File(filesDir, "$fileNamePrefix-stripped.hprof")
    strippedHeapDumpFile.deleteOnExit()

    val sensitiveSourceProvider =
      StoppableFileSourceProvider(sensitiveHeapDumpFile, "sensitive hprof") {
        stopAnalysing
      }

    val strippedHprofSink = strippedHeapDumpFile.outputStream().sink().buffer()
    val stripper = HprofPrimitiveArrayStripper()
    try {
      stripper.stripPrimitiveArrays(sensitiveSourceProvider, strippedHprofSink)
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not strip heap dump" }
      return HeapAnalysisFailure(
          heapDumpFile = strippedHeapDumpFile,
          createdAtTimeMillis = System.currentTimeMillis(),
          dumpDurationMillis = heapDumpDurationMillis,
          analysisDurationMillis = SystemClock.uptimeMillis() - analysisStarted,
          exception = HeapAnalysisException(throwable)
      )
    } finally {
      sensitiveHeapDumpFile.delete()
    }

    val stepListener = OnAnalysisProgressListener { step ->
      check(!stopAnalysing) {
        "Requested stop analysis at step ${step.name}"
      }
      SharkLog.d { "Analysis in progress, working on: ${step.name}" }
    }

    val fileLength = strippedHeapDumpFile.length()
    val analysisSourceProvider = ConstantMemoryMetricsDualSourceProvider(
        StoppableFileSourceProvider(strippedHeapDumpFile, "stripped hprof") {
          stopAnalysing
        })

    return try {
      analysisSourceProvider.openHeapGraph()
    } catch (throwable: Throwable) {
      return HeapAnalysisFailure(
          heapDumpFile = strippedHeapDumpFile,
          createdAtTimeMillis = System.currentTimeMillis(),
          dumpDurationMillis = heapDumpDurationMillis,
          analysisDurationMillis = SystemClock.uptimeMillis() - analysisStarted,
          exception = HeapAnalysisException(throwable)
      )
    }.use { graph ->
      val heapAnalyzer = HeapAnalyzer(stepListener)
      heapAnalyzer.analyze(
          heapDumpFile = strippedHeapDumpFile,
          graph = graph,
          leakingObjectFinder = leakingObjectFinder,
          referenceMatchers = referenceMatchers,
          computeRetainedHeapSize = computeRetainedHeapSize,
          objectInspectors = objectInspectors,
          metadataExtractor = metadataExtractor
      ).let { heapAnalysis ->
        when (heapAnalysis) {
          is HeapAnalysisSuccess -> {
            val lruCacheStats = (graph as HprofHeapGraph).lruCacheStats()
            val randomAccessStats =
              "RandomAccess[" +
                  "bytes=${analysisSourceProvider.randomAccessByteReads}," +
                  "reads=${analysisSourceProvider.randomAccessReadCount}," +
                  "travel=${analysisSourceProvider.randomAccessByteTravel}," +
                  "range=${analysisSourceProvider.byteTravelRange}," +
                  "size=$fileLength" +
                  "]"
            val stats = "$lruCacheStats $randomAccessStats"
            heapAnalysis.copy(
                dumpDurationMillis = heapDumpDurationMillis,
                analysisDurationMillis = SystemClock.uptimeMillis() - analysisStarted,
                metadata = heapAnalysis.metadata + ("Stats" to stats)
            )
          }
          is HeapAnalysisFailure -> heapAnalysis.copy(
              dumpDurationMillis = heapDumpDurationMillis,
              analysisDurationMillis = SystemClock.uptimeMillis() - analysisStarted
          )
        }
      }
    }
  }

  private fun dispatchAnalysisResult(analysis: HeapAnalysis) {

  }

}