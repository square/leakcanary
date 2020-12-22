package leakcanary

import android.app.Activity
import android.app.Application
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O
import android.os.Bundle
import leakcanary.internal.AndroidOFragmentDestroyWatcher
import leakcanary.internal.friendly.noOpDelegate

/**
 * Expects:
 * - Fragments (Support Library, Android X and AOSP) to become weakly reachable soon after they
 * receive the Fragment#onDestroy() callback.
 * - Fragment views (Support Library, Android X and AOSP) to become weakly reachable soon after
 * fragments receive the Fragment#onDestroyView() callback.
 * - Android X view models (both activity and fragment view models) to become weakly reachable soon
 * after they received the ViewModel#onCleared() callback.
 */
class FragmentAndViewModelWatcher(
  private val application: Application,
  private val reachabilityWatcher: ReachabilityWatcher
) : InstallableWatcher {

  private val fragmentDestroyWatchers: List<(Activity) -> Unit> = run {
    val fragmentDestroyWatchers = mutableListOf<(Activity) -> Unit>()

    if (SDK_INT >= O) {
      fragmentDestroyWatchers.add(
        AndroidOFragmentDestroyWatcher(reachabilityWatcher)
      )
    }

    getWatcherIfAvailable(
      ANDROIDX_FRAGMENT_CLASS_NAME,
      ANDROIDX_FRAGMENT_DESTROY_WATCHER_CLASS_NAME,
      reachabilityWatcher
    )?.let {
      fragmentDestroyWatchers.add(it)
    }

    getWatcherIfAvailable(
      ANDROID_SUPPORT_FRAGMENT_CLASS_NAME,
      ANDROID_SUPPORT_FRAGMENT_DESTROY_WATCHER_CLASS_NAME,
      reachabilityWatcher
    )?.let {
      fragmentDestroyWatchers.add(it)
    }
    fragmentDestroyWatchers
  }

  private val lifecycleCallbacks =
    object : Application.ActivityLifecycleCallbacks by noOpDelegate() {
      override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?
      ) {
        for (watcher in fragmentDestroyWatchers) {
          watcher(activity)
        }
      }
    }

  override fun install() {
    application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
  }

  override fun uninstall() {
    application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
  }

  private fun getWatcherIfAvailable(
    fragmentClassName: String,
    watcherClassName: String,
    reachabilityWatcher: ReachabilityWatcher
  ): ((Activity) -> Unit)? {

    return if (classAvailable(fragmentClassName) &&
      classAvailable(watcherClassName)
    ) {
      val watcherConstructor =
        Class.forName(watcherClassName).getDeclaredConstructor(ReachabilityWatcher::class.java)
      @Suppress("UNCHECKED_CAST")
      watcherConstructor.newInstance(reachabilityWatcher) as (Activity) -> Unit
    } else {
      null
    }
  }

  private fun classAvailable(className: String): Boolean {
    return try {
      Class.forName(className)
      true
    } catch (e: Throwable) {
      // e is typically expected to be a ClassNotFoundException
      // Unfortunately, prior to version 25.0.2 of the support library the
      // FragmentManager.FragmentLifecycleCallbacks class was a non static inner class.
      // Our AndroidSupportFragmentDestroyWatcher class is compiled against the static version of
      // the FragmentManager.FragmentLifecycleCallbacks class, leading to the
      // AndroidSupportFragmentDestroyWatcher class being rejected and a NoClassDefFoundError being
      // thrown here. So we're just covering our butts here and catching everything, and assuming
      // any throwable means "can't use this". See https://github.com/square/leakcanary/issues/1662
      false
    }
  }

  companion object {
    private const val ANDROIDX_FRAGMENT_CLASS_NAME = "androidx.fragment.app.Fragment"
    private const val ANDROIDX_FRAGMENT_DESTROY_WATCHER_CLASS_NAME =
      "leakcanary.internal.AndroidXFragmentDestroyWatcher"

    // Using a string builder to prevent Jetifier from changing this string to Android X Fragment
    @Suppress("VariableNaming", "PropertyName")
    private val ANDROID_SUPPORT_FRAGMENT_CLASS_NAME =
      StringBuilder("android.").append("support.v4.app.Fragment")
        .toString()
    private const val ANDROID_SUPPORT_FRAGMENT_DESTROY_WATCHER_CLASS_NAME =
      "leakcanary.internal.AndroidSupportFragmentDestroyWatcher"
  }
}
