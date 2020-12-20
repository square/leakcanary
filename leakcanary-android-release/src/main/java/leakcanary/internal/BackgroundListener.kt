package leakcanary.internal

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import leakcanary.ProcessInfo

/**
 * Tracks whether the app is in background, based on the app's importance.
 */
internal class BackgroundListener(
  private val processInfo: ProcessInfo,
  private val callback: (Boolean) -> Unit
) : ActivityLifecycleCallbacks by noOpDelegate() {

  private val checkAppInBackground: Runnable = object : Runnable {
    override fun run() {
      val appInBackgroundNow = processInfo.isImportanceBackground
      updateBackgroundState(appInBackgroundNow)
      if (!appInBackgroundNow) {
        uiHandler.removeCallbacks(this)
        uiHandler.postDelayed(this, BACKGROUND_REPEAT_DELAY_MS)
      }
    }
  }

  private fun updateBackgroundState(appInBackgroundNow: Boolean) {
    if (appInBackground != appInBackgroundNow) {
      appInBackground = appInBackgroundNow
      callback.invoke(appInBackgroundNow)
    }
  }

  private var appInBackground = false

  fun install(application: Application) {
    application.registerActivityLifecycleCallbacks(this)
    updateBackgroundState(appInBackgroundNow = false)
    checkAppInBackground.run()
  }

  fun uninstall(application: Application) {
    application.unregisterActivityLifecycleCallbacks(this)
    updateBackgroundState(appInBackgroundNow = false)
  }

  override fun onActivityPaused(activity: Activity) {
    uiHandler.removeCallbacks(checkAppInBackground)
    uiHandler.postDelayed(checkAppInBackground, BACKGROUND_DELAY_MS)
  }

  override fun onActivityResumed(activity: Activity) {
    updateBackgroundState(appInBackgroundNow = false)
    uiHandler.removeCallbacks(checkAppInBackground)
  }

  companion object {
    private const val BACKGROUND_DELAY_MS = 1000L
    private const val BACKGROUND_REPEAT_DELAY_MS = 5000L
  }
}