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
import leakcanary.AnalysisResult
import leakcanary.AnalyzerProgressListener
import leakcanary.CanaryLog
import leakcanary.HeapAnalysisFailure
import leakcanary.HeapAnalysisSuccess
import leakcanary.HeapAnalyzer
import leakcanary.HeapDump
import leakcanary.LeakingInstance
import leakcanary.NoPathToInstance
import leakcanary.WeakReferenceCleared
import leakcanary.WeakReferenceMissing
import java.io.File
import java.io.IOException

/**
 * This service runs in a separate process to avoid slowing down the app process or making it run
 * out of memory.
 */
internal class HeapAnalyzerService : ForegroundService(
    HeapAnalyzerService::class.java.simpleName, R.string.leak_canary_notification_analysing
), AnalyzerProgressListener {

  override fun onHandleIntentInForeground(intent: Intent?) {
    if (intent == null) {
      CanaryLog.d("HeapAnalyzerService received a null intent, ignoring.")
      return
    }
    val heapDump = intent.getSerializableExtra(
        HEAPDUMP_EXTRA
    ) as HeapDump

    val heapAnalyzer = HeapAnalyzer(this)

    val heapAnalysis = heapAnalyzer.checkForLeaks(heapDump)

    when (heapAnalysis) {
      is HeapAnalysisFailure -> {
        val result =
          AnalysisResult.failure(heapAnalysis.exception, heapAnalysis.analysisDurationMillis)
        AnalysisResultService.sendResultToListener(this, heapAnalysis.heapDump, result)
      }
      is HeapAnalysisSuccess -> {
        heapAnalysis.retainedInstances.forEachIndexed nextResult@{ index, retainedInstance ->
          val result = when (retainedInstance) {
            is WeakReferenceMissing -> {
              AnalysisResult.noLeak(
                  retainedInstance.referenceKey, referenceName = "unknown (weak ref gced)",
                  className = "unknown (weak ref gced)",
                  analysisDurationMs = heapAnalysis.analysisDurationMillis, watchDurationMs = 0L
              )
            }
            is WeakReferenceCleared -> AnalysisResult.noLeak(
                retainedInstance.referenceKey, retainedInstance.referenceName,
                retainedInstance.instanceClassName, heapAnalysis.analysisDurationMillis,
                retainedInstance.watchDurationMillis
            )
            is NoPathToInstance -> AnalysisResult.noLeak(
                retainedInstance.referenceKey, retainedInstance.referenceName,
                retainedInstance.instanceClassName, heapAnalysis.analysisDurationMillis,
                retainedInstance.watchDurationMillis
            )
            is LeakingInstance -> AnalysisResult.leakDetected(
                retainedInstance.referenceKey, retainedInstance.referenceName,
                retainedInstance.excludedLeak,
                retainedInstance.instanceClassName, retainedInstance.leakTrace,
                retainedInstance.retainedHeapSize, heapAnalysis.analysisDurationMillis,
                retainedInstance.watchDurationMillis
            )
          }

          val fakeFileHeapDump: HeapDump
          if (index > 0) {
            // TODO 1 analysis = many leaks, which is currently unsupported by the rest of the pipeline.
            // We temporarily ignore this problem by replacing the heapdump file with a fake file for
            // all heap dumps but the first one.
            val newFile = File(
                heapDump.heapDumpFile.parentFile,
                index.toString() + heapDump.heapDumpFile.name
            )
            try {
              val created = newFile.createNewFile()
              if (!created) {
                return@nextResult
              }
            } catch (e: IOException) {
              return@nextResult
            }

            fakeFileHeapDump = heapDump.buildUpon()
                .heapDumpFile(
                    newFile
                )
                .build()
          } else {
            fakeFileHeapDump = heapDump
          }
          AnalysisResultService.sendResultToListener(this, fakeFileHeapDump, result)
        }
      }
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
