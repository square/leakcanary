package leakcanary

import android.app.Application
import android.app.PendingIntent
import com.squareup.leakcanary.core.R
import leakcanary.internal.LeakCanaryUtils
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeaksDbHelper
import leakcanary.internal.activity.screen.GroupListScreen
import leakcanary.internal.activity.screen.HeapAnalysisFailureScreen
import leakcanary.internal.activity.screen.HeapAnalysisListScreen
import leakcanary.internal.activity.screen.HeapAnalysisSuccessScreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DefaultAnalysisResultListener : (Application, HeapAnalysis) -> Unit {
  override fun invoke(
    application: Application,
    heapAnalysis: HeapAnalysis
  ) {

    // TODO better log that include leakcanary version, exclusions, etc.
    CanaryLog.d("%s", heapAnalysis)

    val movedHeapDump = renameHeapdump(heapAnalysis.heapDumpFile)

    val updatedHeapAnalysis = when (heapAnalysis) {
      is HeapAnalysisFailure -> heapAnalysis.copy(heapDumpFile = movedHeapDump)
      is HeapAnalysisSuccess -> heapAnalysis.copy(heapDumpFile = movedHeapDump)
    }

    val id = LeaksDbHelper(application)
        .writableDatabase.use { db ->
      HeapAnalysisTable.insert(db, updatedHeapAnalysis)
    }

    val contentTitle = when (heapAnalysis) {
      is HeapAnalysisFailure -> application.getString(R.string.leak_canary_analysis_failed)
      // TODO better text and res
      is HeapAnalysisSuccess -> "Leak analysis done"
    }

    val screenToShow = when (heapAnalysis) {
      is HeapAnalysisFailure -> HeapAnalysisFailureScreen(id)
      is HeapAnalysisSuccess -> HeapAnalysisSuccessScreen(id)
    }

    val pendingIntent = LeakActivity.createPendingIntent(
        application, arrayListOf(GroupListScreen(), HeapAnalysisListScreen(), screenToShow)
    )

    val contentText = application.getString(R.string.leak_canary_notification_message)
    showNotification(application, pendingIntent, contentTitle, contentText)
  }

  private fun showNotification(
    application: Application,
    pendingIntent: PendingIntent?,
    contentTitle: String,
    contentText: String
  ) {
    val notificationId = 0x00F06D
    LeakCanaryUtils.showNotification(
        application, contentTitle, contentText, pendingIntent, notificationId
    )
  }

  private fun renameHeapdump(heapDumpFile: File): File {
    val fileName = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS'.hprof'", Locale.US).format(Date())

    val newFile = File(heapDumpFile.parent, fileName)
    val renamed = heapDumpFile.renameTo(newFile)
    if (!renamed) {
      CanaryLog.d(
          "Could not rename heap dump file %s to %s", heapDumpFile.path, newFile.path
      )
    }
    return newFile
  }
}