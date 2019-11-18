package leakcanary

import android.app.Application
import com.squareup.leakcanary.core.R
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.NotificationType.LEAKCANARY_RESULT
import leakcanary.internal.Notifications
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.LeaksDbHelper
import leakcanary.internal.activity.screen.HeapAnalysisFailureScreen
import leakcanary.internal.activity.screen.HeapDumpsScreen
import leakcanary.internal.activity.screen.HeapDumpScreen
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog

/**
 * Default [OnHeapAnalyzedListener] implementation, which will store the analysis to disk and
 * show a notification summarizing the result.
 */
class DefaultOnHeapAnalyzedListener(private val application: Application) : OnHeapAnalyzedListener {

  override fun onHeapAnalyzed(heapAnalysis: HeapAnalysis) {
    SharkLog.d { "$heapAnalysis" }

    val (id, groupProjections) = LeaksDbHelper(application)
        .writableDatabase.use { db ->
      val id = HeapAnalysisTable.insert(db, heapAnalysis)
      id to LeakTable.retrieveHeapDumpLeaks(db, id)
    }

    val (contentTitle, screenToShow) = when (heapAnalysis) {
      is HeapAnalysisFailure -> application.getString(
          R.string.leak_canary_analysis_failed
      ) to HeapAnalysisFailureScreen(id)
      is HeapAnalysisSuccess -> {
        var leakCount = 0
        var newLeakCount = 0
        var knownLeakCount = 0
        var libraryLeakCount = 0

        for ((_, projection) in groupProjections) {
          leakCount += projection.leakCount
          when {
            projection.isLibraryLeak -> libraryLeakCount += projection.leakCount
            projection.isNew -> newLeakCount += projection.leakCount
            else -> knownLeakCount += projection.leakCount
          }
        }

        application.getString(
            R.string.leak_canary_analysis_success_notification, leakCount, newLeakCount,
            knownLeakCount, libraryLeakCount
        ) to HeapDumpScreen(id)
      }
    }

    val pendingIntent = LeakActivity.createPendingIntent(
        application, arrayListOf(HeapDumpsScreen(), screenToShow)
    )

    val contentText = application.getString(R.string.leak_canary_notification_message)

    Notifications.showNotification(
        application, contentTitle, contentText, pendingIntent,
        R.id.leak_canary_notification_analysis_result,
        LEAKCANARY_RESULT
    )
  }

  companion object {
    fun create(): OnHeapAnalyzedListener =
      DefaultOnHeapAnalyzedListener(InternalLeakCanary.application)
  }
}