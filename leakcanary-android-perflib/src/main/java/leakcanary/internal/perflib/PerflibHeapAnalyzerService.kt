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
package leakcanary.internal.perflib

import android.content.Intent
import android.os.Process
import android.os.Process.setThreadPriority
import com.squareup.leakcanary.R
import leakcanary.AnalyzerProgressListener
import leakcanary.CanaryLog
import leakcanary.HeapDump
import leakcanary.internal.AnalysisResultService
import leakcanary.internal.ForegroundService
import leakcanary.internal.HeapAnalyzers

/**
 * This service runs in a separate process to avoid slowing down the app process or making it run
 * out of memory.
 */
internal class PerflibHeapAnalyzerService : ForegroundService(
    PerflibHeapAnalyzerService::class.java.simpleName, R.string.leak_canary_notification_analysing
), AnalyzerProgressListener {

  override fun onHandleIntentInForeground(intent: Intent?) {
    if (intent == null) {
      CanaryLog.d("PerflibHeapAnalyzerService received a null intent, ignoring.")
      return
    }
    // Since we're running in the main process we should be careful not to impact it.
    setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    val heapDump = intent.getSerializableExtra(HeapAnalyzers.HEAPDUMP_EXTRA) as HeapDump

    val heapAnalyzer = PerflibHeapAnalyzer(this)
    val heapAnalysis = heapAnalyzer.checkForLeaks(heapDump)

    AnalysisResultService.sendResult(this, heapAnalysis)
  }

  override fun onProgressUpdate(step: AnalyzerProgressListener.Step) {
    val percent = (100f * step.ordinal / AnalyzerProgressListener.Step.values().size).toInt()
    CanaryLog.d("Analysis in progress, working on: %s", step.name)
    val lowercase = step.name.replace("_", " ")
        .toLowerCase()
    val message = lowercase.substring(0, 1).toUpperCase() + lowercase.substring(1)
    showForegroundNotification(100, percent, false, message)
  }
}
