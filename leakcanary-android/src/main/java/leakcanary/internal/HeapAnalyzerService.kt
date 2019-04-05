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
import leakcanary.AbstractAnalysisResultService
import leakcanary.AnalyzerProgressListener
import leakcanary.CanaryLog
import leakcanary.HeapAnalyzer
import leakcanary.HeapDump
import com.squareup.leakcanary.R
import leakcanary.internal.LeakCanaryInternals.Companion.setEnabledBlocking
import java.io.File
import java.io.IOException

/**
 * This service runs in a separate process to avoid slowing down the app process or making it run
 * out of memory.
 */
class HeapAnalyzerService : ForegroundService(
    HeapAnalyzerService::class.java.simpleName, R.string.leak_canary_notification_analysing
), AnalyzerProgressListener {

  override fun onHandleIntentInForeground(intent: Intent?) {
    if (intent == null) {
      CanaryLog.d("HeapAnalyzerService received a null intent, ignoring.")
      return
    }
    val listenerClassName = intent.getStringExtra(
        LISTENER_CLASS_EXTRA
    )
    val heapDump = intent.getSerializableExtra(
        HEAPDUMP_EXTRA
    ) as HeapDump

    val heapAnalyzer = HeapAnalyzer(
        heapDump.excludedRefs, this,
        heapDump.reachabilityInspectorClasses
    )

    val analysisResults = heapAnalyzer.checkForLeaks(
        heapDump.heapDumpFile,
        heapDump.computeRetainedHeapSize
    )

    var i = 0
    for (result in analysisResults) {
      val fakeFileHeapDump: HeapDump
      if (i > 0) {
        // TODO 1 analysis = many leaks, which is currently unsupported by the rest of the pipeline.
        // We temporarily ignore this problem by replacing the heapdump file with a fake file for
        // all heap dumps but the first one.
        val newFile = File(
            heapDump.heapDumpFile.parentFile,
            i.toString() + heapDump.heapDumpFile.name
        )
        try {
          val created = newFile.createNewFile()
          if (!created) {
            continue
          }
        } catch (e: IOException) {
          continue
        }

        fakeFileHeapDump = heapDump.buildUpon()
            .heapDumpFile(
                newFile
            )
            .build()
      } else {
        fakeFileHeapDump = heapDump
      }
      AbstractAnalysisResultService.sendResultToListener(
          this, listenerClassName, fakeFileHeapDump,
          result
      )
      i++
    }
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

    private const val LISTENER_CLASS_EXTRA = "listener_class_extra"
    private const val HEAPDUMP_EXTRA = "heapdump_extra"

    fun runAnalysis(
      context: Context,
      heapDump: HeapDump,
      listenerServiceClass: Class<out AbstractAnalysisResultService>
    ) {
      setEnabledBlocking(context, HeapAnalyzerService::class.java, true)
      setEnabledBlocking(context, listenerServiceClass, true)
      val intent = Intent(context, HeapAnalyzerService::class.java)
      intent.putExtra(LISTENER_CLASS_EXTRA, listenerServiceClass.name)
      intent.putExtra(HEAPDUMP_EXTRA, heapDump)
      ContextCompat.startForegroundService(context, intent)
    }
  }
}
