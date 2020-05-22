package leakcanary.internal

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

private val hasAndroidXFragmentActivity: Boolean by lazy {
  try {
    Class.forName("androidx.fragment.app.FragmentActivity")
    true
  } catch (ignored: Throwable) {
    false
  }
}

internal fun Activity.onAndroidXFragmentViewDestroyed(block: () -> Unit) {
  if (!hasAndroidXFragmentActivity) {
    return
  }
  if (this is FragmentActivity) {
    supportFragmentManager.registerFragmentLifecycleCallbacks(
        object : FragmentManager.FragmentLifecycleCallbacks() {
          override fun onFragmentViewDestroyed(
            fm: FragmentManager,
            fragment: Fragment
          ) {
            block()
          }
        }, true
    )
  }
}
