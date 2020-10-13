package leakcanary.internal

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Handler
import android.os.Looper

internal class BackgroundListener(
  private val callback: (Boolean) -> Unit
) : ActivityLifecycleCallbacks by noOpDelegate() {

  private val handler = Handler(Looper.getMainLooper())

  private val checkAppInBackground: Runnable = object: Runnable {
    private val memoryOutState = RunningAppProcessInfo()
    override fun run() {
      ActivityManager.getMyMemoryState(memoryOutState)
      val appInBackgroundNow = memoryOutState.importance >= RunningAppProcessInfo.IMPORTANCE_BACKGROUND
      updateBackgroundState(appInBackgroundNow)
      if (!appInBackgroundNow) {
        handler.removeCallbacks(this)
        handler.postDelayed(this, BACKGROUND_REPEAT_DELAY_MS)
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
    handler.removeCallbacks(checkAppInBackground)
    handler.postDelayed(checkAppInBackground, BACKGROUND_DELAY_MS)
  }

  override fun onActivityResumed(activity: Activity) {
    updateBackgroundState(appInBackgroundNow = false)
    handler.removeCallbacks(checkAppInBackground)
  }

  companion object {
    private const val BACKGROUND_DELAY_MS = 1000L
    private const val BACKGROUND_REPEAT_DELAY_MS = 5000L
  }
}