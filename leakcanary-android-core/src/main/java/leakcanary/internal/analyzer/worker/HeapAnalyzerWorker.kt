package leakcanary.internal.analyzer.worker

import android.app.Notification
import android.content.Context
import android.os.Process
import androidx.work.WorkerParameters
import com.squareup.leakcanary.core.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import leakcanary.LeakCanary
import leakcanary.internal.LeakDirectoryProvider
import leakcanary.internal.analyzer.service.HeapAnalyzerService
import leakcanary.internal.analyzer.service.HeapAnalyzerService.Companion.HEAPDUMP_DURATION_MILLIS_EXTRA
import leakcanary.internal.analyzer.service.HeapAnalyzerService.Companion.HEAPDUMP_FILE_EXTRA
import leakcanary.internal.analyzer.service.HeapAnalyzerService.Companion.HEAPDUMP_REASON_EXTRA
import shark.*
import java.io.File
import java.io.IOException
import java.util.*

class HeapAnalyzerWorker(context: Context, params: WorkerParameters) :
  ForegroundWorker(context, params), OnAnalysisProgressListener {

  private val heapDumpFilePath: String?
    get() = inputData.getString(HEAPDUMP_FILE_EXTRA)

  private val heapDumpFile: File
    get() = File(heapDumpFilePath!!)

  private val heapDumpReason: String
    get() = inputData.getString(HEAPDUMP_REASON_EXTRA)!!

  private val heapDumpDurationMillis: Long
    get() = inputData.getLong(HEAPDUMP_DURATION_MILLIS_EXTRA, -1)

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    if (heapDumpFilePath == null) {
      SharkLog.d { "HeapAnalyzerService received a null or empty intent, ignoring." }
      return@withContext Result.failure()
    }

    showNotification(getNotification())
    // Since we're running in the main process we should be careful not to impact it.
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

    val config = LeakCanary.config
    val heapAnalysis = if (heapDumpFile.exists()) {
      analyzeHeap(heapDumpFile, config)
    } else {
      missingFileFailure(heapDumpFile)
    }
    val fullHeapAnalysis = when (heapAnalysis) {
      is HeapAnalysisSuccess -> heapAnalysis.copy(
        dumpDurationMillis = heapDumpDurationMillis,
        metadata = heapAnalysis.metadata + ("Heap dump reason" to heapDumpReason)
      )
      is HeapAnalysisFailure -> heapAnalysis.copy(dumpDurationMillis = heapDumpDurationMillis)
    }
    onAnalysisProgress(OnAnalysisProgressListener.Step.REPORTING_HEAP_ANALYSIS)
    config.onHeapAnalyzedListener.onHeapAnalyzed(fullHeapAnalysis)
    return@withContext Result.Success().also { removeNotification() }
  }

  private fun analyzeHeap(
    heapDumpFile: File,
    config: LeakCanary.Config
  ): HeapAnalysis {
    val heapAnalyzer = HeapAnalyzer(this)

    val proguardMappingReader = try {
      ProguardMappingReader(applicationContext.assets.open(HeapAnalyzerService.PROGUARD_MAPPING_FILE_NAME))
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

  override fun getNotification(): Notification {
    return buildForegroundNotification(
      max = 100, progress = 0, indeterminate = true,
      contentText = applicationContext.getString(R.string.leak_canary_notification_foreground_text)
    )
  }

  override fun getNotificationTitleResId(): Int {
    return R.string.leak_canary_notification_analysing
  }

  override fun onAnalysisProgress(step: OnAnalysisProgressListener.Step) {
    val percent =
      (100f * step.ordinal / OnAnalysisProgressListener.Step.values().size).toInt()
    SharkLog.d { "Analysis in progress, working on: ${step.name}" }
    val lowercase = step.name.replace("_", " ")
      .toLowerCase(Locale.US)
    val message = lowercase.substring(0, 1).toUpperCase(Locale.US) + lowercase.substring(1)
    val updatedNotification = buildForegroundNotification(100, percent, false, message)
    showNotification(updatedNotification)
  }
}
