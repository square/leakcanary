package leakcanary.internal.analyzer

import android.content.Context
import android.os.Process
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import leakcanary.LeakCanary
import leakcanary.internal.LeakDirectoryProvider
import shark.HeapAnalysis
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.OnAnalysisProgressListener
import shark.ProguardMappingReader
import shark.SharkLog
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * BaseHeapAnalyzer class is added to perform Heap Analyzing
 * without needing to add too much code in the Worker implementation.
 */
class BaseHeapAnalyzer constructor(
  private val context: Context,
  private val inputData: Data?,
  private val analysisProgress: (percent: Int, message: String) -> Unit
) : OnAnalysisProgressListener {

  fun startAnalyzing() {
    val heapDumpReason = inputData?.getString(HEAPDUMP_REASON_EXTRA)
    val heapDumpFilePath = inputData?.getString(HEAPDUMP_FILE_PATH_EXTRA)
    val heapDumpDurationMillis = inputData?.getLong(
      HEAPDUMP_DURATION_MILLIS_EXTRA, -1
    ) ?: -1

    // Since we're running in the main process we should be careful not to impact it.
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

    // At this point, `heapDumpFilePath` will never be @null
    val heapDumpFile = File(heapDumpFilePath!!)

    val config = LeakCanary.config
    val heapAnalysis = if (heapDumpFile.exists()) {
      analyzeHeap(heapDumpFile, config)
    } else {
      missingFileFailure(heapDumpFile)
    }
    val fullHeapAnalysis = when (heapAnalysis) {
      is HeapAnalysisSuccess -> heapAnalysis.copy(
        dumpDurationMillis = heapDumpDurationMillis,
        metadata = heapAnalysis.metadata + ("Heap dump reason" to heapDumpReason!!)
      )
      is HeapAnalysisFailure -> heapAnalysis.copy(dumpDurationMillis = heapDumpDurationMillis)
    }
    onAnalysisProgress(OnAnalysisProgressListener.Step.REPORTING_HEAP_ANALYSIS)
    config.onHeapAnalyzedListener.onHeapAnalyzed(fullHeapAnalysis)
  }

  private fun analyzeHeap(
    heapDumpFile: File,
    config: LeakCanary.Config
  ): HeapAnalysis {
    val heapAnalyzer = HeapAnalyzer(this)

    val proguardMappingReader = try {
      ProguardMappingReader(context.assets.open(PROGUARD_MAPPING_FILE_NAME))
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

  override fun onAnalysisProgress(step: OnAnalysisProgressListener.Step) {
    val percent =
      (100f * step.ordinal / OnAnalysisProgressListener.Step.values().size).toInt()
    SharkLog.d { "Analysis in progress, working on: ${step.name}" }
    val lowercase = step.name.replace("_", " ")
      .toLowerCase(Locale.US)
    val message = lowercase.substring(0, 1).toUpperCase(Locale.US) + lowercase.substring(1)
    analysisProgress(percent, message)
  }

  companion object {
    const val HEAPDUMP_FILE_PATH_EXTRA = "HEAPDUMP_FILE_PATH_EXTRA"
    const val HEAPDUMP_DURATION_MILLIS_EXTRA = "HEAPDUMP_DURATION_MILLIS_EXTRA"
    const val HEAPDUMP_REASON_EXTRA = "HEAPDUMP_REASON_EXTRA"
    const val PROGUARD_MAPPING_FILE_NAME = "leakCanaryObfuscationMapping.txt"

    fun runAnalysis(
      context: Context,
      heapDumpFile: File,
      heapDumpDurationMillis: Long? = null,
      heapDumpReason: String = "Unknown"
    ) {

      // Cannot use `workDataOf` as it requires JVM target 1.6,
      // so we are falling back to plain old Data.Builder() pattern
      val inputData = Data.Builder().apply {
        putString(HEAPDUMP_FILE_PATH_EXTRA, heapDumpFile.absolutePath)
        putString(HEAPDUMP_REASON_EXTRA, heapDumpReason)
        heapDumpDurationMillis?.let {
          putLong(HEAPDUMP_DURATION_MILLIS_EXTRA, heapDumpDurationMillis)
        }
      }.build()
      val workManager = WorkManager.getInstance(context)
      val heapAnalyzerWorker =
        OneTimeWorkRequest.Builder(HeapAnalyzerWorker::class.java)
          .addTag("heap_analyzer_worker")
          .setInputData(inputData)
          .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
          .build()

      // Enqueue a Unique Work with an **Id** as the developer can schedule
      // multiple analysis tasks manually, from the LeakCanary extension App.
      workManager.enqueueUniqueWork(
        heapAnalyzerWorker.id.toString(),
        ExistingWorkPolicy.KEEP, heapAnalyzerWorker
      )
    }
  }
}
