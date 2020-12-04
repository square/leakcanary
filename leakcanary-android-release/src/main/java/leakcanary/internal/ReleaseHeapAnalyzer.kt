package leakcanary.internal

import android.app.Application
import android.content.res.Resources.NotFoundException
import android.os.Debug
import android.os.SystemClock
import leakcanary.HeapAnalysisConfig
import okio.buffer
import okio.sink
import shark.AndroidResourceIdNames
import shark.CloseableHeapGraph
import shark.ConstantMemoryMetricsDualSourceProvider
import shark.HeapAnalysis
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.HprofHeapGraph
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofPrimitiveArrayStripper
import shark.OnAnalysisProgressListener
import shark.SharkLog
import java.io.File
import java.util.UUID

internal class ReleaseHeapAnalyzer(
  private val application: Application,
  private val config: HeapAnalysisConfig,
  private val listener: (HeapAnalysis) -> Unit
) {

  @Volatile
  private var stopAnalysing = false

  private val backgroundHandler by lazy {
    startBackgroundHandlerThread("heap-analyzer")
  }

  private val analyzeHeap = Runnable {
    stopAnalysing = false
    val result = dumpAndAnalyzeHeap()
    result.heapDumpFile.delete()
    listener(result)
  }

  fun removeAllHeapDumpFiles() {
    backgroundHandler.post {
      val filesDir = application.filesDir!!
      val heapDumpFiles = filesDir.listFiles { _, name ->
        name.startsWith("heap-") && name.endsWith(".hprof")
      }
      heapDumpFiles?.forEach { it.delete() }
    }
  }

  fun start() {
    backgroundHandler.removeCallbacks(analyzeHeap)
    backgroundHandler.post(analyzeHeap)
  }

  fun stop() {
    stopAnalysing = true
    backgroundHandler.removeCallbacks(analyzeHeap)
  }

  private fun dumpAndAnalyzeHeap(): HeapAnalysis {
    val filesDir = application.filesDir!!
    val fileNamePrefix = "heap-${UUID.randomUUID()}"
    val sensitiveHeapDumpFile = File(filesDir, "$fileNamePrefix.hprof").apply {
      // Any call to System.exit(0) will run shutdown hooks that will attempt to remove this
      // file. Note that this is best effort, and won't delete if the VM is killed by the system.
      deleteOnExit()
    }

    if (!config.stripHeapDump) {
      saveResourceIdNamesToMemory()
    }

    val heapDumpStart = SystemClock.uptimeMillis()
    saveHeapDumpTime(heapDumpStart)

    var dumpDurationMillis = -1L
    var analysisDurationMillis = -1L
    var heapDumpFile = sensitiveHeapDumpFile

    try {
      dumpHeap(sensitiveHeapDumpFile)
      dumpDurationMillis = SystemClock.uptimeMillis() - heapDumpStart

      val stripDuration = measureDurationMillis {
        if (config.stripHeapDump) {
          val strippedHeapDumpFile = File(filesDir, "$fileNamePrefix-stripped.hprof").apply {
            deleteOnExit()
          }
          heapDumpFile = strippedHeapDumpFile
          try {
            stripHeapDump(sensitiveHeapDumpFile, strippedHeapDumpFile)
          } finally {
            sensitiveHeapDumpFile.delete()
          }
        }
      }

      return analyzeHeapWithStats(heapDumpFile).let { (heapAnalysis, stats) ->
        when (heapAnalysis) {
          is HeapAnalysisSuccess -> {
            val metadata = heapAnalysis.metadata.toMutableMap()
            metadata["Stats"] = stats
            if (config.stripHeapDump) {
              metadata["Hprof stripping duration"] = "$stripDuration ms"
            }
            heapAnalysis.copy(
                dumpDurationMillis = dumpDurationMillis,
                metadata = metadata
            )
          }
          is HeapAnalysisFailure -> heapAnalysis.copy(
              dumpDurationMillis = dumpDurationMillis,
              analysisDurationMillis = (SystemClock.uptimeMillis() - heapDumpStart) - dumpDurationMillis
          )
        }
      }
    } catch (throwable: Throwable) {
      if (dumpDurationMillis == -1L) {
        dumpDurationMillis = SystemClock.uptimeMillis() - heapDumpStart
      }
      if (analysisDurationMillis == -1L) {
        analysisDurationMillis = (SystemClock.uptimeMillis() - heapDumpStart) - dumpDurationMillis
      }
      return HeapAnalysisFailure(
          heapDumpFile = heapDumpFile,
          createdAtTimeMillis = System.currentTimeMillis(),
          dumpDurationMillis = dumpDurationMillis,
          analysisDurationMillis = analysisDurationMillis,
          exception = HeapAnalysisException(throwable)
      )
    }
  }

  private fun saveResourceIdNamesToMemory() {
    val resources = application.resources
    AndroidResourceIdNames.saveToMemory(
        getResourceTypeName = { id ->
          try {
            resources.getResourceTypeName(id)
          } catch (e: NotFoundException) {
            null
          }
        },
        getResourceEntryName = { id ->
          try {
            resources.getResourceEntryName(id)
          } catch (e: NotFoundException) {
            null
          }
        })
  }

  private fun saveHeapDumpTime(heapDumpUptimeMillis: Long) {
    try {
      Class.forName("leakcanary.KeyedWeakReference")
          .getDeclaredField("heapDumpUptimeMillis")
          .apply { isAccessible = true }
          .set(null, heapDumpUptimeMillis)
    } catch (ignored: Throwable) {
      SharkLog.d(ignored) { "KeyedWeakReference.heapDumpUptimeMillis not updated" }
    }
  }

  private fun dumpHeap(heapDumpFile: File) {
    Debug.dumpHprofData(heapDumpFile.absolutePath)

    check(heapDumpFile.exists()) {
      "File does not exist after dump"
    }

    check(heapDumpFile.length() > 0L) {
      "File has length ${heapDumpFile.length()} after dump"
    }
  }

  private fun stripHeapDump(
    sourceHeapDumpFile: File,
    strippedHeapDumpFile: File
  ) {
    val sensitiveSourceProvider =
      StoppableFileSourceProvider(sourceHeapDumpFile, "sensitive hprof") {
        stopAnalysing
      }

    val strippedHprofSink = strippedHeapDumpFile.outputStream().sink().buffer()
    val stripper = HprofPrimitiveArrayStripper()

    stripper.stripPrimitiveArrays(sensitiveSourceProvider, strippedHprofSink)
  }

  private fun analyzeHeapWithStats(heapDumpFile: File): Pair<HeapAnalysis, String> {
    val fileLength = heapDumpFile.length()
    val analysisSourceProvider = ConstantMemoryMetricsDualSourceProvider(
        StoppableFileSourceProvider(heapDumpFile, "stripped hprof") {
          stopAnalysing
        })

    return analysisSourceProvider.openHeapGraph().use { graph ->
      val heapAnalysis = analyzeHeap(heapDumpFile, graph)
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
      (heapAnalysis to stats)
    }
  }

  private fun analyzeHeap(
    analyzedHeapDumpFile: File,
    graph: CloseableHeapGraph
  ): HeapAnalysis {
    val stepListener = OnAnalysisProgressListener { step ->
      check(!stopAnalysing) {
        "Requested stop analysis at step ${step.name}"
      }
      SharkLog.d { "Analysis in progress, working on: ${step.name}" }
    }

    val heapAnalyzer = HeapAnalyzer(stepListener)
    return heapAnalyzer.analyze(
        heapDumpFile = analyzedHeapDumpFile,
        graph = graph,
        leakingObjectFinder = config.leakingObjectFinder,
        referenceMatchers = config.referenceMatchers,
        computeRetainedHeapSize = config.computeRetainedHeapSize,
        objectInspectors = config.objectInspectors,
        metadataExtractor = config.metadataExtractor
    )
  }

}