package leakcanary

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.squareup.leakcanary.core.R
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.InternalLeakCanary.FormFactor.TV
import leakcanary.internal.NotificationType.LEAKCANARY_MAX
import leakcanary.internal.Notifications
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeaksDbHelper
import leakcanary.internal.activity.screen.HeapAnalysisFailureScreen
import leakcanary.internal.activity.screen.HeapDumpScreen
import leakcanary.internal.activity.screen.HeapDumpsScreen
import leakcanary.internal.navigation.Screen
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog

/**
 * Default [OnHeapAnalyzedListener] implementation, which will store the analysis to disk and
 * show a notification summarizing the result.
 */
class DefaultOnHeapAnalyzedListener(private val application: Application) : OnHeapAnalyzedListener {
  private val handler = Handler(Looper.getMainLooper())

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

    if (InternalLeakCanary.formFactor == TV) {
      showToast()
    } else {
      showNotification(screenToShow, contentTitle)
    }
  }

  private fun showNotification(
    screenToShow: Screen,
    contentTitle: String
  ) {
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

  /**
   * Android TV devices do not have notifications, therefore the only easy and non-invasive way
   * to communicate with user is via Toast messages. These are used just to grab user attention and
   * to direct them to Logcat where a much more detailed report will be printed.
   */
  private fun showToast() {
    // Post the Toast into main thread and wrap it with try-catch in case Toast crashes (it happens)
    handler.post {
      try {
        Toast.makeText(
            application,
            "Analysis complete, please check Logcat",
            Toast.LENGTH_LONG
        )
            .show()
      } catch (exception: Exception) {
        // Toasts are prone to crashing, ignore
      }
    }
  }

  companion object {
    fun create(): OnHeapAnalyzedListener =
      DefaultOnHeapAnalyzedListener(InternalLeakCanary.application)
  }
}