package leakcanary.internal.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import com.squareup.leakcanary.core.R

/**
 * Toast helper for Android TV preconfigured with LeakCanary icon.
 *
 * Shows toast with custom view layout, custom toast views are deprecated from API level 30 on (see
 * [docs](https://developer.android.com/reference/android/widget/Toast#getView()))
 */
internal object TvToast {

  /**
   * Make an Android TV toast.
   * Don't forget to call [Toast.show] to display the toast!
   * @param activity Currently resumed [Activity] to display toast on. Note that it's not [Context]
   *        to prevent passing application context that could lead to crashes on older platforms.
   * @param text The text to show. Can be formatted text.
   */
  @SuppressLint("ShowToast")
  fun makeText(
    activity: Activity,
    text: CharSequence
  ): Toast {
    val inflater = LayoutInflater.from(activity)
    val toast = Toast(activity)

    toast.apply {
      setGravity(Gravity.CENTER_VERTICAL, 0, 0)
      duration = Toast.LENGTH_LONG
      view = inflater.inflate(R.layout.leak_canary_heap_dump_toast, null).also {
        it.findViewById<TextView>(R.id.leak_canary_toast_text).text = text
      }
    }

    return toast
  }
}
