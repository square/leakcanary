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
import androidx.core.content.ContextCompat
import com.squareup.leakcanary.R
import leakcanary.CanaryLog
import leakcanary.HeapAnalysis
import leakcanary.HeapAnalysisFailure
import leakcanary.HeapAnalysisSuccess
import leakcanary.HeapDump
import leakcanary.Serializables
import leakcanary.internal.activity.screen.HeapAnalysisFailureScreen
import leakcanary.internal.activity.screen.HeapAnalysisListScreen
import leakcanary.internal.activity.screen.HeapAnalysisSuccessScreen
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.activity.db.LeaksDbHelper
import leakcanary.internal.activity.screen.GroupListScreen
import leakcanary.save
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs into the main process and handles the result of an analysis
 */
class AnalysisResultService : ForegroundService(
    AnalysisResultService::class.java.name,
    R.string.leak_canary_notification_reporting
) {

  override fun onHandleIntentInForeground(intent: Intent?) {
    if (intent == null) {
      CanaryLog.d("AnalysisResultService received a null intent, ignoring.")
      return
    }
    if (!intent.hasExtra(HEAP_ANALYSIS_PATH_EXTRA)) {
      onAnalysisResultFailure(getString(R.string.leak_canary_result_failure_no_disk_space));
      return
    }
    val heapAnalysisFile = File(intent.getStringExtra(HEAP_ANALYSIS_PATH_EXTRA))

    val heapAnalysis = Serializables.load<HeapAnalysis>(heapAnalysisFile)
    heapAnalysisFile.delete()
    if (heapAnalysis == null) {
      onAnalysisResultFailure(getString(R.string.leak_canary_result_failure_no_file))
      return
    }

    try {
      onHeapAnalyzed(heapAnalysis)
    } finally {
      heapAnalysis.heapDump.heapDumpFile.delete()
    }
  }

  private fun onHeapAnalyzed(heapAnalysis: HeapAnalysis) {
    // TODO better log that include leakcanary version, exclusions, etc.
    CanaryLog.d("%s", heapAnalysis)

    val movedHeapDump = heapAnalysis.heapDump.buildUpon()
        .heapDumpFile(renameHeapdump(heapAnalysis.heapDump))
        .build()

    val updatedHeapAnalysis = when (heapAnalysis) {
      is HeapAnalysisFailure -> heapAnalysis.copy(heapDump = movedHeapDump)
      is HeapAnalysisSuccess -> heapAnalysis.copy(heapDump = movedHeapDump)
    }

    val id = LeaksDbHelper(this)
        .writableDatabase.use { db ->
      HeapAnalysisTable.insert(db, updatedHeapAnalysis)
    }

    // TODO better text and res
    val contentTitle = "Leak analysis done"

    val screenToShow = when (heapAnalysis) {
      is HeapAnalysisFailure -> HeapAnalysisFailureScreen(id)
      is HeapAnalysisSuccess -> HeapAnalysisSuccessScreen(id)
    }

    val pendingIntent = LeakActivity.createPendingIntent(
        this, arrayListOf(GroupListScreen(), HeapAnalysisListScreen(), screenToShow)
    )

    val contentText = getString(R.string.leak_canary_notification_message)
    showNotification(pendingIntent, contentTitle, contentText)
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

  private fun renameHeapdump(heapDump: HeapDump): File {
    val fileName = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS'.hprof'", Locale.US).format(Date())

    val newFile = File(heapDump.heapDumpFile.parent, fileName)
    val renamed = heapDump.heapDumpFile.renameTo(newFile)
    if (!renamed) {
      CanaryLog.d(
          "Could not rename heap dump file %s to %s", heapDump.heapDumpFile.path, newFile.path
      )
    }
    return newFile
  }

  companion object {

    private const val HEAP_ANALYSIS_PATH_EXTRA = "HEAP_ANALYSIS_PATH_EXTRA"

    fun sendResult(
      context: Context,
      heapAnalysis: HeapAnalysis
    ) {
      val intent = Intent(context, AnalysisResultService::class.java)

      val heapAnalysisFile = File(
          heapAnalysis.heapDump.heapDumpFile.parentFile,
          heapAnalysis.heapDump.heapDumpFile.name + ".analysis"
      )

      val saved = heapAnalysis.save(heapAnalysisFile)
      if (saved) {
        intent.putExtra(HEAP_ANALYSIS_PATH_EXTRA, heapAnalysisFile.absolutePath)
      }
      ContextCompat.startForegroundService(context, intent)
    }
  }
}
