package leakcanary.internal.navigation

import android.app.Activity
import android.content.Context
import android.os.Build.VERSION
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup

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