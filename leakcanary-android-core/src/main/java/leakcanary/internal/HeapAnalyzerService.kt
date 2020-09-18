/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package leakcanary.internal

import android.content.Context
import android.content.Intent
import android.os.Build.VERSION.SDK_INT
import android.os.Process
import com.squareup.leakcanary.core.R
import leakcanary.LeakCanary
import leakcanary.LeakCanary.Config
import shark.HeapAnalysis
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.OnAnalysisProgressListener
import shark.OnAnalysisProgressListener.Step.REPORTING_HEAP_ANALYSIS
import shark.ProguardMappingReader
import shark.SharkLog
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * This service runs in a main app process.
 */
internal class HeapAnalyzerService : ForegroundService(
    HeapAnalyzerService::class.java.simpleName,
    R.string.leak_canary_notification_analysing,
    R.id.leak_canary_notification_analyzing_heap
), OnAnalysisProgressListener {

  override fun onHandleIntentInForeground(intent: Intent?) {
    if (intent == null || !intent.hasExtra(HEAPDUMP_FILE_EXTRA)) {
      SharkLog.d { "HeapAnalyzerService received a null or empty intent, ignoring." }
      return
    }

    // Since we're running in the main process we should be careful not to impact it.
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    val heapDumpFile = intent.getSerializableExtra(HEAPDUMP_FILE_EXTRA) as File
    val heapDumpDurationMillis = intent.getLongExtra(HEAPDUMP_DURATION_MILLIS, -1)

    val config = LeakCanary.config
    val heapAnalysis = if (heapDumpFile.exists()) {
      analyzeHeap(heapDumpFile, config)
    } else {
      missingFileFailure(heapDumpFile)
    }
    val fullHeapAnalysis = when (heapAnalysis) {
      is HeapAnalysisSuccess -> heapAnalysis.copy(dumpDurationMillis = heapDumpDurationMillis)
      is HeapAnalysisFailure -> heapAnalysis.copy(dumpDurationMillis = heapDumpDurationMillis)
    }
    onAnalysisProgress(REPORTING_HEAP_ANALYSIS)
    config.onHeapAnalyzedListener.onHeapAnalyzed(fullHeapAnalysis)
  }

  private fun analyzeHeap(
    heapDumpFile: File,
    config: Config
  ): HeapAnalysis {
    val heapAnalyzer = HeapAnalyzer(this)

    val proguardMappingReader = try {
      ProguardMappingReader(assets.open(PROGUARD_MAPPING_FILE_NAME))
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
    showForegroundNotification(100, percent, false, message)
  }

  companion object {
    private const val HEAPDUMP_FILE_EXTRA = "HEAPDUMP_FILE_EXTRA"
    private const val HEAPDUMP_DURATION_MILLIS = "HEAPDUMP_DURATION_MILLIS"
    private const val PROGUARD_MAPPING_FILE_NAME = "leakCanaryObfuscationMapping.txt"

    fun runAnalysis(
      context: Context,
      heapDumpFile: File,
      heapDumpDurationMillis: Long? = null
    ) {
      val intent = Intent(context, HeapAnalyzerService::class.java)
      intent.putExtra(HEAPDUMP_FILE_EXTRA, heapDumpFile)
      heapDumpDurationMillis?.let {
        intent.putExtra(HEAPDUMP_DURATION_MILLIS, heapDumpDurationMillis)
      }
      startForegroundService(context, intent)
    }

    private fun startForegroundService(
      context: Context,
      intent: Intent
    ) {
      if (SDK_INT >= 26) {
        context.startForegroundService(intent)
      } else {
        // Pre-O behavior.
        context.startService(intent)
      }
    }
  }
}
