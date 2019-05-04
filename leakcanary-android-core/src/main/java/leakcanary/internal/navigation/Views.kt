package leakcanary.internal.navigation

import android.app.Activity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes

internal fun ViewGroup.inflate(@LayoutRes layoutResId: Int) = LayoutInflater.from(context)
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