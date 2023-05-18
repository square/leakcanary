package leakcanary

import com.squareup.leakcanary.core.R
import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.HeapAnalysisDone
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.friendly.mainHandler
import leakcanary.internal.tv.TvToast
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog

object TvEventListener : EventListener {

  private val appContext = InternalLeakCanary.application

  override fun onEvent(event: Event) {
    when (event) {
      is HeapAnalysisDone<*> -> {
        showToast(event.heapAnalysis)
        printIntentInfo()
      }
    }
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
          appContext.getString(
            R.string.leak_canary_tv_analysis_success,
            heapAnalysis.applicationLeaks.size,
            heapAnalysis.libraryLeaks.size
          )
        }
        is HeapAnalysisFailure -> appContext.getString(R.string.leak_canary_tv_analysis_failure)
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
    SharkLog.d {"""
      ====================================
      ANDROID TV LAUNCH INTENT
      ====================================
      Run the following adb command to display the list of leaks:

      adb shell am start -n "${appContext.packageName}/${leakClass.`package`?.name}.LeakLauncherActivity"
      ====================================""".trimIndent()
    }
  }
}
