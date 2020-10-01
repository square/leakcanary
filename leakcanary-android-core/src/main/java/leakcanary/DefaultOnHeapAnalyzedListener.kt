package leakcanary

import android.app.Application
import android.os.Handler
import android.os.Looper
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
import leakcanary.internal.activity.screen.LeakTraceWrapper
import leakcanary.internal.navigation.Screen
import leakcanary.internal.tv.TvToast
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog

/**
 * Default [OnHeapAnalyzedListener] implementation, which will store the analysis to disk and
 * show a notification summarizing the result.
 */
class DefaultOnHeapAnalyzedListener private constructor(private val applicationProvider: () -> Application) :
    OnHeapAnalyzedListener {

  // Kept this constructor for backward compatibility of public API.
  @Deprecated(
      message = "Use DefaultOnHeapAnalyzedListener.create() instead",
      replaceWith = ReplaceWith("DefaultOnHeapAnalyzedListener.create()")
  )
  constructor(application: Application) : this({ application })

  private val mainHandler = Handler(Looper.getMainLooper())

  private val application: Application by lazy { applicationProvider() }

  override fun onHeapAnalyzed(heapAnalysis: HeapAnalysis) {
    SharkLog.d { "\u200B\n${LeakTraceWrapper.wrap(heapAnalysis.toString(), 120)}" }

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
      showToast(heapAnalysis)
      printIntentInfo()
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
  private fun showToast(heapAnalysis: HeapAnalysis) {
    mainHandler.post {
      val resumedActivity = InternalLeakCanary.resumedActivity ?: return@post
      val message: String = when (heapAnalysis) {
        is HeapAnalysisSuccess -> {
          application.getString(
              R.string.leak_canary_tv_analysis_success,
              heapAnalysis.applicationLeaks.size,
              heapAnalysis.libraryLeaks.size
          )
        }
        is HeapAnalysisFailure -> application.getString(R.string.leak_canary_tv_analysis_failure)
      }
      TvToast.makeText(resumedActivity, message)
          .show()
    }
  }

  /**
   * Android TV with API 26+ has a bug where the launcher icon doesn't appear, so users won't know how
   * to launch LeakCanary Activity.
   * This method prints an adb command that launched LeakCanary into the logcat
   */
  private fun printIntentInfo() {
    val leakClass = LeakActivity::class.java
    SharkLog.d {
      """====================================
  ANDROID TV LAUNCH INTENT
  ====================================
  Run the following adb command to display the list of leaks:
  
  adb shell am start -n "${application.packageName}/${leakClass.`package`?.name}.LeakLauncherActivity"
  ===================================="""
    }
  }

  companion object {
    fun create(): OnHeapAnalyzedListener =
      DefaultOnHeapAnalyzedListener { InternalLeakCanary.application }
  }
}