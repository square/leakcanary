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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import com.squareup.leakcanary.R
import leakcanary.AnalysisResult
import leakcanary.CanaryLog
import leakcanary.HeapDump
import leakcanary.LeakCanary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs into the main process and handles the result of an analysis
 */
internal class AnalysisResultService : ForegroundService(
    AnalysisResultService::class.java.name,
    R.string.leak_canary_notification_reporting
) {

  override fun onHandleIntentInForeground(intent: Intent?) {
    if (intent == null) {
      CanaryLog.d("AnalysisResultService received a null intent, ignoring.")
      return
    }
    if (!intent.hasExtra(ANALYZED_HEAP_PATH_EXTRA)) {
      onAnalysisResultFailure(getString(R.string.leak_canary_result_failure_no_disk_space));
      return
    }
    val analyzedHeapFile = File(intent.getStringExtra(ANALYZED_HEAP_PATH_EXTRA))
    val analyzedHeap = AnalyzedHeap.load(analyzedHeapFile)
    if (analyzedHeap == null) {
      onAnalysisResultFailure(getString(R.string.leak_canary_result_failure_no_file))
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
  private fun onHeapAnalyzed(analyzedHeap: AnalyzedHeap) {
    var heapDump = analyzedHeap.heapDump
    val result = analyzedHeap.result

    val leakInfo = LeakCanary.leakInfo(this, heapDump, result)
    CanaryLog.d("%s", leakInfo)

    heapDump = renameHeapdump(heapDump)
    val resultSaved = saveResult(heapDump, result)

    val contentTitle: String
    if (resultSaved) {
      val pendingIntent = DisplayLeakActivity.createPendingIntent(this, result.referenceKey)
      if (result.failure != null) {
        contentTitle = getString(R.string.leak_canary_analysis_failed)
      } else {
        val className = LeakCanaryUtils.classSimpleName(result.className!!)
        if (result.leakFound) {
          contentTitle = if (result.retainedHeapSize == AnalysisResult.RETAINED_HEAP_SKIPPED) {
            if (result.excludedLeak) {
              getString(R.string.leak_canary_leak_excluded, className)
            } else {
              getString(R.string.leak_canary_class_has_leaked, className)
            }
          } else {
            val size = Formatter.formatShortFileSize(this, result.retainedHeapSize)
            if (result.excludedLeak) {
              getString(R.string.leak_canary_leak_excluded_retaining, className, size)
            } else {
              getString(R.string.leak_canary_class_has_leaked_retaining, className, size)
            }
          }
        } else {
          contentTitle = getString(R.string.leak_canary_class_no_leak, className)
        }
      }
      val contentText = getString(R.string.leak_canary_notification_message)
      showNotification(pendingIntent, contentTitle, contentText)
    } else {
      onAnalysisResultFailure(getString(R.string.leak_canary_could_not_save_text))
    }
  }

  /**
   * Called when there was an error saving or loading the analysis result. This will be called from
   * a background intent service thread.
   */
  private fun onAnalysisResultFailure(failureMessage: String) {
    CanaryLog.d(failureMessage)
    val failureTitle = getString(R.string.leak_canary_result_failure_title)
    showNotification(null, failureTitle, failureMessage)
  }

  private fun showNotification(
    pendingIntent: PendingIntent?,
    contentTitle: String,
    contentText: String
  ) {
    // New notification id every second.
    val notificationId = (SystemClock.uptimeMillis() / 1000).toInt()
    LeakCanaryUtils.showNotification(
        this, contentTitle, contentText, pendingIntent!!,
        notificationId
    )
  }

  private fun saveResult(
    heapDump: HeapDump,
    result: AnalysisResult
  ): Boolean {
    val resultFile = AnalyzedHeap.save(heapDump, result)
    return resultFile != null
  }

  private fun renameHeapdump(heapDump: HeapDump): HeapDump {
    val fileName = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS'.hprof'", Locale.US).format(Date())

    val newFile = File(heapDump.heapDumpFile.parent, fileName)
    val renamed = heapDump.heapDumpFile.renameTo(newFile)
    if (!renamed) {
      CanaryLog.d(
          "Could not rename heap dump file %s to %s", heapDump.heapDumpFile.path, newFile.path
      )
    }
    return heapDump.buildUpon()
        .heapDumpFile(newFile)
        .build()
  }

  companion object {

    private const val ANALYZED_HEAP_PATH_EXTRA = "analyzed_heap_path_extra"

    fun sendResultToListener(
      context: Context,
      heapDump: HeapDump,
      result: AnalysisResult
    ) {
      val intent = Intent(context, AnalysisResultService::class.java)

      val analyzedHeapFile = AnalyzedHeap.save(heapDump, result)
      if (analyzedHeapFile != null) {
        intent.putExtra(ANALYZED_HEAP_PATH_EXTRA, analyzedHeapFile.absolutePath)
      }
      ContextCompat.startForegroundService(context, intent)
    }
  }
}
