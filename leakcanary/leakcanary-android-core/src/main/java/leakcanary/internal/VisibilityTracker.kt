package leakcanary.internal

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SCREEN_OFF
import android.content.Intent.ACTION_SCREEN_ON
import android.content.IntentFilter
import leakcanary.internal.friendly.noOpDelegate

internal class VisibilityTracker(
  private val listener: (Boolean) -> Unit
) : Application.ActivityLifecycleCallbacks by noOpDelegate(), BroadcastReceiver() {

  private var startedActivityCount = 0

  /**
   * Visible activities are any activity started but not stopped yet. An activity can be paused
   * yet visible: this will happen when another activity shows on top with a transparent background
   * and the activity behind won't get touch inputs but still need to render / animate.
   */
  private var hasVisibleActivities: Boolean = false

  /**
   * Assuming screen on by default.
   */
  private var screenOn: Boolean = true

  private var lastUpdate: Boolean = false

  override fun onActivityStarted(activity: Activity) {
    startedActivityCount++
    if (!hasVisibleActivities && startedActivityCount == 1) {
      hasVisibleActivities = true
      updateVisible()
    }
  }

  override fun onActivityStopped(activity: Activity) {
    // This could happen if the callbacks were registered after some activities were already
    // started. In that case we effectively considers those past activities as not visible.
    if (startedActivityCount > 0) {
      startedActivityCount--
    }
    if (hasVisibleActivities && startedActivityCount == 0 && !activity.isChangingConfigurations) {
      hasVisibleActivities = false
      updateVisible()
    }
  }

  override fun onReceive(
    context: Context,
    intent: Intent
  ) {
    screenOn = intent.action != ACTION_SCREEN_OFF
    updateVisible()
  }

  private fun updateVisible() {
    val visible = screenOn && hasVisibleActivities
    if (visible != lastUpdate) {
      lastUpdate = visible
      listener.invoke(visible)
    }
  }
}

internal fun Application.registerVisibilityListener(listener: (Boolean) -> Unit) {
  val visibilityTracker = VisibilityTracker(listener)
  registerActivityLifecycleCallbacks(visibilityTracker)
  registerReceiver(visibilityTracker, IntentFilter().apply {
    addAction(ACTION_SCREEN_ON)
    addAction(ACTION_SCREEN_OFF)
  })
}