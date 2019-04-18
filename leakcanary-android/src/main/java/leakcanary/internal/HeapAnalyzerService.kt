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
import androidx.core.content.ContextCompat
import com.squareup.leakcanary.R
import leakcanary.AnalyzerProgressListener
import leakcanary.CanaryLog
import leakcanary.HeapAnalyzer
import leakcanary.HeapDump

/**
 * This service runs in a separate process to avoid slowing down the app process or making it run
 * out of memory.
 */
internal class HeapAnalyzerService : ForegroundService(
    HeapAnalyzerService::class.java.simpleName, R.string.leak_canary_notification_analysing
), AnalyzerProgressListener {

  override fun onHandleIntentInForeground(intent: Intent?) {
    if (CanaryLog.logger == null) {
      CanaryLog.logger = DefaultCanaryLog()
    }

    if (intent == null) {
      CanaryLog.d("HeapAnalyzerService received a null intent, ignoring.")
      return
    }
    val heapDump = intent.getSerializableExtra(HEAPDUMP_EXTRA) as HeapDump

    val heapAnalysis =
      if (heapDump.useExperimentalHeapParser) {
        leakcanary.experimental.HeapAnalyzer(this)
            .checkForLeaks(heapDump)
      } else HeapAnalyzer(this).checkForLeaks(heapDump)

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

  companion object {

    private const val HEAPDUMP_EXTRA = "heapdump_extra"

    fun runAnalysis(
      context: Context,
      heapDump: HeapDump
    ) {
      val intent = Intent(context, HeapAnalyzerService::class.java)
      intent.putExtra(HEAPDUMP_EXTRA, heapDump)
      ContextCompat.startForegroundService(context, intent)
    }
  }
}
