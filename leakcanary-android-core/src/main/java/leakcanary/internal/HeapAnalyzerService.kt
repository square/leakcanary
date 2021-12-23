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
import leakcanary.AndroidHeapAnalyzer
import leakcanary.EventListener.Event.HeapDumped
import shark.SharkLog
import java.io.File

/**
 * This service runs in a main app process.
 */
internal class HeapAnalyzerService : ForegroundService(
  HeapAnalyzerService::class.java.simpleName,
  R.string.leak_canary_notification_analysing,
  R.id.leak_canary_notification_analyzing_heap
) {

  override fun onHandleIntentInForeground(intent: Intent?) {
    if (intent == null || !intent.hasExtra(HEAPDUMP_FILE_EXTRA)) {
      SharkLog.d { "HeapAnalyzerService received a null or empty intent, ignoring." }
      return
    }

    // Since we're running in the main process we should be careful not to impact it.
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    val heapDumpFile = intent.getSerializableExtra(HEAPDUMP_FILE_EXTRA) as File
    val heapDumpReason = intent.getStringExtra(HEAPDUMP_REASON_EXTRA)!!
    val heapDumpDurationMillis = intent.getLongExtra(HEAPDUMP_DURATION_MILLIS_EXTRA, -1)

    AndroidHeapAnalyzer().runAnalysisBlocking(HeapDumped(heapDumpFile, heapDumpDurationMillis, heapDumpReason))
  }



  companion object {
    private const val HEAPDUMP_FILE_EXTRA = "HEAPDUMP_FILE_EXTRA"
    private const val HEAPDUMP_DURATION_MILLIS_EXTRA = "HEAPDUMP_DURATION_MILLIS_EXTRA"
    private const val HEAPDUMP_REASON_EXTRA = "HEAPDUMP_REASON_EXTRA"

    fun runAnalysis(
      context: Context,
      heapDumpFile: File,
      heapDumpDurationMillis: Long? = null,
      heapDumpReason: String = "Unknown"
    ) {
      val intent = Intent(context, HeapAnalyzerService::class.java)
      intent.putExtra(HEAPDUMP_FILE_EXTRA, heapDumpFile)
      intent.putExtra(HEAPDUMP_REASON_EXTRA, heapDumpReason)
      heapDumpDurationMillis?.let {
        intent.putExtra(HEAPDUMP_DURATION_MILLIS_EXTRA, heapDumpDurationMillis)
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
