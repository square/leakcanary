package leakcanary

import android.app.Application
import com.squareup.leakcanary.core.R
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.NotificationType.LEAKCANARY_MAX
import leakcanary.internal.Notifications
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeaksDbHelper
import leakcanary.internal.activity.screen.HeapAnalysisFailureScreen
import leakcanary.internal.activity.screen.HeapDumpScreen
import leakcanary.internal.activity.screen.HeapDumpsScreen
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

    val id = LeaksDbHelper(application).writableDatabase.use { db ->
      HeapAnalysisTable.insert(db, heapAnalysis)
    }

    val (contentTitle, screenToShow) = when (heapAnalysis) {
      is HeapAnalysisFailure -> application.getString(
          R.string.leak_canary_analysis_failed
      ) to HeapAnalysisFailureScreen(id)
      is HeapAnalysisSuccess -> {
        val retainedObjectCount = heapAnalysis.allLeaks.sumBy { it.leakTraces.size }
        val leakTypeCount = heapAnalysis.applicationLeaks.size + heapAnalysis.libraryLeaks.size
        application.getString(
            R.string.leak_canary_analysis_success_notification, retainedObjectCount, leakTypeCount
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
        LEAKCANARY_MAX
    )
  }

  companion object {
    fun create(): OnHeapAnalyzedListener =
      DefaultOnHeapAnalyzedListener(InternalLeakCanary.application)
  }
}