package leakcanary.internal.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.widget.TextView
import android.widget.Toast
import com.squareup.leakcanary.core.R

/**
 * Toast helper for Android TV preconfigured with LeakCanary icon.
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
  fun makeText(activity: Activity, text: CharSequence): Toast {
    val toast: Toast = Toast.makeText(activity, text, Toast.LENGTH_LONG)
    val textView = toast.view.findViewById<TextView>(android.R.id.message)
    textView.setCompoundDrawablesWithIntrinsicBounds(R.drawable.leak_canary_icon, 0, 0, 0)
    textView.compoundDrawablePadding =
      activity.resources.getDimensionPixelSize(R.dimen.leak_canary_toast_icon_tv_padding)
    return toast
  }
}