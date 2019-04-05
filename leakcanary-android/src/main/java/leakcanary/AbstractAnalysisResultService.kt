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
package leakcanary

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.squareup.leakcanary.R.string
import leakcanary.internal.ForegroundService
import java.io.File

abstract class AbstractAnalysisResultService : ForegroundService(
    AbstractAnalysisResultService::class.java.name,
    string.leak_canary_notification_reporting
) {

  override fun onHandleIntentInForeground(intent: Intent?) {
    if (intent == null) {
      CanaryLog.d(
          "AbstractAnalysisResultService received a null intent, ignoring."
      )
      return
    }
    if (!intent.hasExtra(
            ANALYZED_HEAP_PATH_EXTRA
        )) {
      onAnalysisResultFailure(getString(
          string.leak_canary_result_failure_no_disk_space
      ))
      return
    }
    val analyzedHeapFile = File(intent.getStringExtra(
        ANALYZED_HEAP_PATH_EXTRA
    ))
    val analyzedHeap = AnalyzedHeap.load(analyzedHeapFile)
    if (analyzedHeap == null) {
      onAnalysisResultFailure(getString(
          string.leak_canary_result_failure_no_file
      ))
      return
    }
    try {
      onHeapAnalyzed(analyzedHeap)
    } finally {

      analyzedHeap.heapDump.heapDumpFile.delete()

      analyzedHeap.selfFile.delete()
    }
  }

  /**
   * Called after a heap dump is analyzed, whether or not a leak was found.
   * In [AnalyzedHeap.result] check [AnalysisResult.leakFound] and [AnalysisResult.excludedLeak] to
   * see if there was a leak and if it can be ignored.
   *
   * This will be called from a background intent service thread.
   *
   * It's OK to block here and wait for the heap dump to be uploaded.
   *
   * The analyzed heap file and heap dump file will be deleted immediately after this callback
   * returns.
   */
  protected open fun onHeapAnalyzed(analyzedHeap: AnalyzedHeap) {
  }

  /**
   * Called when there was an error saving or loading the analysis result. This will be called from
   * a background intent service thread.
   */
  protected open fun onAnalysisResultFailure(failureMessage: String) {
    CanaryLog.d(failureMessage)
  }

  companion object {

    private const val ANALYZED_HEAP_PATH_EXTRA = "analyzed_heap_path_extra"

    fun sendResultToListener(
      context: Context,
      listenerServiceClassName: String,
      heapDump: HeapDump,
      result: AnalysisResult
    ) {
      val listenerServiceClass = Class.forName(listenerServiceClassName)

      val intent = Intent(context, listenerServiceClass)

      val analyzedHeapFile = AnalyzedHeap.save(heapDump, result)
      if (analyzedHeapFile != null) {
        intent.putExtra(ANALYZED_HEAP_PATH_EXTRA, analyzedHeapFile.absolutePath)
      }
      ContextCompat.startForegroundService(context, intent)
    }
  }
}
