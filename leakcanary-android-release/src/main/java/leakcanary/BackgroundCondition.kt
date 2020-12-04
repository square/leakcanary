package leakcanary

import android.app.Application
import leakcanary.HeapAnalysisCondition.Result
import leakcanary.HeapAnalysisCondition.Result.StartAnalysis
import leakcanary.HeapAnalysisCondition.Result.StopAnalysis
import leakcanary.internal.BackgroundListener
import leakcanary.internal.uiHandler

class BackgroundCondition(
  private val application: Application
) : HeapAnalysisCondition() {

  private val backgroundListener: BackgroundListener = BackgroundListener { appInBackgroundNow ->
    val changed = appInBackground != appInBackgroundNow
    appInBackground = appInBackgroundNow
    if (changed) {
      trigger.conditionChanged("app in background is $appInBackgroundNow")
    }
  }

  private var appInBackground = false

  private var backgroundListenerInstalled = false

  override fun evaluate(): Result {
    if (!backgroundListenerInstalled) {
      backgroundListenerInstalled = true
      uiHandler.post {
        backgroundListener.install(application)
      }
    }
    return if (appInBackground) {
      StartAnalysis
    } else {
      StopAnalysis("App is not in background")
    }
  }
}