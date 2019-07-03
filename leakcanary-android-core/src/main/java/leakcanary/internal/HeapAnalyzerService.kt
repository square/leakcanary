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
import android.os.Process
import androidx.core.content.ContextCompat
import com.squareup.leakcanary.core.R
import leakcanary.AnalyzerProgressListener
import leakcanary.AndroidKnownReference
import leakcanary.CanaryLog
import leakcanary.HeapAnalyzer
import leakcanary.LeakCanary
import java.io.File

/**
 * This service runs in a main app process.
 */
internal class HeapAnalyzerService : ForegroundService(
    HeapAnalyzerService::class.java.simpleName,
    R.string.leak_canary_notification_analysing,
    R.id.leak_canary_notification_analyzing_heap
), AnalyzerProgressListener {

  override fun onHandleIntentInForeground(intent: Intent?) {
    if (intent == null) {
      CanaryLog.d("HeapAnalyzerService received a null intent, ignoring.")
      return
    }
    // Since we're running in the main process we should be careful not to impact it.
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    val heapDumpFile = intent.getSerializableExtra(HEAPDUMP_FILE_EXTRA) as File

    if (!heapDumpFile.exists()) {
      throw IllegalStateException(
          "Hprof file missing due to: [${LeakDirectoryProvider.hprofDeleteReason(
              heapDumpFile
          )}] $heapDumpFile"
      )
    }

    val heapAnalyzer = HeapAnalyzer(this)
    val config = LeakCanary.config

    val exclusions = AndroidKnownReference.mapToExclusions(config.knownReferences)

    val heapAnalysis =
      heapAnalyzer.checkForLeaks(
          heapDumpFile, exclusions, config.computeRetainedHeapSize, config.leakTraceInspectors
      )

    config.analysisResultListener(application, heapAnalysis)
  }

  override fun onProgressUpdate(step: AnalyzerProgressListener.Step) {
    val percent = (100f * step.ordinal / AnalyzerProgressListener.Step.values().size).toInt()
    CanaryLog.d("Analysis in progress, working on: %s", step.name)
    val lowercase = step.name.replace("_", " ")
        .toLowerCase()
    val message = lowercase.substring(0, 1).toUpperCase() + lowercase.substring(1)
    showForegroundNotification(100, percent, false, message)
  }

  companion object {
    private const val HEAPDUMP_FILE_EXTRA = "HEAPDUMP_FILE_EXTRA"

    fun runAnalysis(
      context: Context,
      heapDumpFile: File
    ) {
      val intent = Intent(context, HeapAnalyzerService::class.java)
      intent.putExtra(HEAPDUMP_FILE_EXTRA, heapDumpFile)
      ContextCompat.startForegroundService(context, intent)
    }
  }
}
