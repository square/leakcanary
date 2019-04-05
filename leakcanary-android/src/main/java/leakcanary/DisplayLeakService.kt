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

import android.app.PendingIntent
import android.os.SystemClock
import android.text.format.Formatter.formatShortFileSize
import com.squareup.leakcanary.R.string
import leakcanary.internal.DisplayLeakActivity
import leakcanary.internal.LeakCanaryInternals
import leakcanary.internal.LeakCanaryInternals.Companion.classSimpleName
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs leak analysis results, and then shows a notification which will start [ ].
 *
 *
 * You can extend this class and override [.afterDefaultHandling] to add custom behavior, e.g. uploading the heap dump.
 */
class DisplayLeakService : AbstractAnalysisResultService() {

  override fun onHeapAnalyzed(analyzedHeap: AnalyzedHeap) {
    var heapDump = analyzedHeap.heapDump
    val result = analyzedHeap.result

    val leakInfo = LeakCanary.leakInfo(this, heapDump, result, true)
    CanaryLog.d("%s", leakInfo)

    heapDump = renameHeapdump(heapDump)
    val resultSaved = saveResult(heapDump, result)

    val contentTitle: String
    if (resultSaved) {
      val pendingIntent = DisplayLeakActivity.createPendingIntent(this, result.referenceKey)
      if (result.failure != null) {
        contentTitle = getString(string.leak_canary_analysis_failed)
      } else {
        val className = classSimpleName(result.className!!)
        if (result.leakFound) {
          if (result.retainedHeapSize == AnalysisResult.RETAINED_HEAP_SKIPPED) {
            if (result.excludedLeak) {
              contentTitle = getString(string.leak_canary_leak_excluded, className)
            } else {
              contentTitle = getString(
                  string.leak_canary_class_has_leaked, className)
            }
          } else {
            val size = formatShortFileSize(this, result.retainedHeapSize)
            if (result.excludedLeak) {
              contentTitle =
                getString(string.leak_canary_leak_excluded_retaining, className, size)
            } else {
              contentTitle =
                getString(string.leak_canary_class_has_leaked_retaining, className, size)
            }
          }
        } else {
          contentTitle = getString(string.leak_canary_class_no_leak, className)
        }
      }
      val contentText = getString(string.leak_canary_notification_message)
      showNotification(pendingIntent, contentTitle, contentText)
    } else {
      onAnalysisResultFailure(getString(
          string.leak_canary_could_not_save_text
      ))
    }

    afterDefaultHandling(heapDump, result, leakInfo)
  }

  override fun onAnalysisResultFailure(failureMessage: String) {
    super.onAnalysisResultFailure(failureMessage)
    val failureTitle = getString(string.leak_canary_result_failure_title)
    showNotification(null, failureTitle, failureMessage)
  }

  private fun showNotification(
    pendingIntent: PendingIntent?,
    contentTitle: String,
    contentText: String
  ) {
    // New notification id every second.
    val notificationId = (SystemClock.uptimeMillis() / 1000).toInt()
    LeakCanaryInternals.showNotification(
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
          "Could not rename heap dump file %s to %s", heapDump.heapDumpFile.path,
          newFile.path
      )
    }
    return heapDump.buildUpon()
        .heapDumpFile(newFile)
        .build()
  }

  /**
   * You can override this method and do a blocking call to a server to upload the leak trace and
   * the heap dump. Don't forget to check [AnalysisResult.leakFound] and [ ][AnalysisResult.excludedLeak] first.
   */
  protected fun afterDefaultHandling(
    heapDump: HeapDump,
    result: AnalysisResult,
    leakInfo: String
  ) {
  }
}
