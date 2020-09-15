package leakcanary

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Build.VERSION
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import leakcanary.AndroidLeakFixes.Companion.checkMainThread
import leakcanary.internal.onAndroidXFragmentViewDestroyed
import shark.SharkLog

/**
 * @see [AndroidLeakFixes.VIEW_LOCATION_HOLDER].
 */
@SuppressLint("NewApi")
object ViewLocationHolderLeakFix {

  private var groupAndOutChildren: Pair<ViewGroup, ArrayList<View>>? = null
  private var failedClearing = false

  internal fun applyFix(application: Application) {
    if (VERSION.SDK_INT != 28) {
      return
    }
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks
    by AndroidLeakFixes.noOpDelegate() {

      override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?
      ) {
        activity.onAndroidXFragmentViewDestroyed {
          uncheckedClearStaticPool(application)
        }
      }

      override fun onActivityDestroyed(activity: Activity) {
        uncheckedClearStaticPool(application)
      }
    })
  }

  /**
   * Clears the ViewGroup.ViewLocationHolder.sPool static pool.
   */
  fun clearStaticPool(application: Application) {
    checkMainThread()
    if (VERSION.SDK_INT != 28) {
      return
    }
    uncheckedClearStaticPool(application)
  }

  private fun uncheckedClearStaticPool(application: Application) {
    if (failedClearing) {
      return
    }
    try {
      if (groupAndOutChildren == null) {
        val viewGroup = FrameLayout(application)
        // ViewLocationHolder.MAX_POOL_SIZE = 32
        for (i in 0 until 32) {
          val childView = View(application)
          viewGroup.addView(childView)
        }
        groupAndOutChildren = viewGroup to ArrayList()
      }
      val (group, outChildren) = groupAndOutChildren!!
      group.addChildrenForAccessibility(outChildren)
    } catch (ignored :Throwable) {
      SharkLog.d(ignored) {
        "Failed to clear ViewLocationHolder leak, will not try again."
      }
      failedClearing = true
    }
  }
}
