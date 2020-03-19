package leakcanary.internal.navigation

import android.app.Activity
import android.content.Context
import android.os.Build.VERSION
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import com.squareup.leakcanary.core.R

internal fun ViewGroup.inflate(layoutResId: Int) = LayoutInflater.from(context)
    .inflate(layoutResId, this, false)!!

internal val View.activity
  get() = context as Activity

@Suppress("UNCHECKED_CAST")
internal fun <T : Activity> View.activity() = context as T

internal fun View.onCreateOptionsMenu(onCreateOptionsMenu: (Menu) -> Unit) {
  activity<NavigatingActivity>().onCreateOptionsMenu = onCreateOptionsMenu
  activity.invalidateOptionsMenu()
}

internal fun View.goTo(screen: Screen) {
  activity<NavigatingActivity>().goTo(screen)
}

internal fun View.goBack() {
  activity<NavigatingActivity>().goBack()
}

internal fun Context.getColorCompat(id: Int): Int {
  return if (VERSION.SDK_INT >= 23) {
    getColor(id)
  } else {
    resources.getColor(id)
  }
}

internal fun View.onScreenExiting(block: () -> Unit) {
  @Suppress("UNCHECKED_CAST")
  var callbacks = getTag(R.id.leak_canary_notification_on_screen_exit) as MutableList<() -> Unit>?
  if (callbacks == null) {
    callbacks = mutableListOf<() -> Unit>()
    setTag(R.id.leak_canary_notification_on_screen_exit, callbacks)
  }
  callbacks.add(block)
}

internal fun View.notifyScreenExiting() {
  @Suppress("UNCHECKED_CAST")
  val callbacks = getTag(R.id.leak_canary_notification_on_screen_exit)
      as MutableList<() -> Unit>?
  callbacks?.forEach { it.invoke() }
}