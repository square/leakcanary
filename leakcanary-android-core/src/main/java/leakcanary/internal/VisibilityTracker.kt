package leakcanary.internal

import android.app.Activity
import android.app.Application
import leakcanary.internal.InternalLeakCanary.noOpDelegate

internal class VisibilityTracker(
  private val listener: (Boolean) -> Unit
) :
    Application.ActivityLifecycleCallbacks by noOpDelegate() {

  private var startedActivityCount = 0

  /**
   * Visible activities are any activity started but not stopped yet. An activity can be paused
   * yet visible: this will happen when another activity shows on top with a transparent background
   * and the activity behind won't get touch inputs but still need to render / animate.
   */
  private var hasVisibleActivities: Boolean = false

  override fun onActivityStarted(activity: Activity) {
    startedActivityCount++
    if (!hasVisibleActivities && startedActivityCount == 1) {
      hasVisibleActivities = true
      listener.invoke(true)
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
      listener.invoke(false)
    }
  }
}

internal fun Application.registerVisibilityListener(listener: (Boolean) -> Unit) {
  registerActivityLifecycleCallbacks(VisibilityTracker(listener))
}