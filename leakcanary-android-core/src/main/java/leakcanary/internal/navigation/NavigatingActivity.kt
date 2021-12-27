package leakcanary.internal.navigation

import android.app.Activity
import android.content.Intent
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils.loadAnimation
import com.squareup.leakcanary.core.R

/**
 * A simple backstack navigating activity
 */
internal abstract class NavigatingActivity : Activity() {

  private lateinit var backstack: ArrayList<BackstackFrame>
  private lateinit var currentScreen: Screen

  private lateinit var container: ViewGroup
  private lateinit var currentView: View

  var onCreateOptionsMenu = NO_MENU

  fun installNavigation(
    savedInstanceState: Bundle?,
    container: ViewGroup
  ) {
    this.container = container

    if (savedInstanceState == null) {
      backstack = ArrayList()
      val screens = parseIntentScreens(intent)
      currentScreen = if (screens.isNotEmpty()) {
        screens.dropLast(1)
          .forEach { screen ->
            backstack.add(BackstackFrame(screen))
          }
        screens.last()
      } else {
        getLauncherScreen()
      }
    } else {
      currentScreen = savedInstanceState.getSerializable("currentScreen") as Screen
      @Suppress("UNCHECKED_CAST")
      backstack = savedInstanceState.getParcelableArrayList<Parcelable>(
        "backstack"
      ) as ArrayList<BackstackFrame>
    }
    currentView = currentScreen.createView(container)
    container.addView(currentView)

    actionBar?.run {
      setHomeButtonEnabled(true)
      setDisplayHomeAsUpEnabled(true)
    }
    screenUpdated()
  }

  override fun onNewIntent(intent: Intent) {
    val screens = parseIntentScreens(intent)
    if (screens.isNotEmpty()) {
      backstack.clear()
      screens.dropLast(1)
        .forEach { screen ->
          backstack.add(BackstackFrame(screen))
        }
      goTo(screens.last())
    }
  }

  abstract fun parseIntentScreens(intent: Intent): List<Screen>

  open fun getLauncherScreen(): Screen {
    TODO("Launcher activities should override getLauncherScreen()")
  }

  public override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putSerializable("currentScreen", currentScreen)
    outState.putParcelableArrayList("backstack", backstack)
  }

  override fun onBackPressed() {
    if (backstack.size > 0) {
      goBack()
      return
    }
    super.onBackPressed()
  }

  fun resetTo(screen: Screen) {
    onCreateOptionsMenu = NO_MENU

    currentView.startAnimation(loadAnimation(this, R.anim.leak_canary_exit_alpha))
    container.removeView(currentView)
    currentView.notifyScreenExiting()

    backstack.clear()

    currentScreen = screen
    currentView = currentScreen.createView(container)
    currentView.startAnimation(loadAnimation(this, R.anim.leak_canary_enter_alpha))
    container.addView(currentView)

    screenUpdated()
  }

  fun goTo(screen: Screen) {
    onCreateOptionsMenu = NO_MENU

    currentView.startAnimation(loadAnimation(this, R.anim.leak_canary_exit_forward))
    container.removeView(currentView)
    currentView.notifyScreenExiting()
    val backstackFrame = BackstackFrame(currentScreen, currentView)
    backstack.add(backstackFrame)

    currentScreen = screen
    currentView = currentScreen.createView(container)
    currentView.startAnimation(loadAnimation(this, R.anim.leak_canary_enter_forward))
    container.addView(currentView)

    screenUpdated()
  }

  fun refreshCurrentScreen() {
    onCreateOptionsMenu = NO_MENU
    container.removeView(currentView)
    currentView.notifyScreenExiting()
    currentView = currentScreen.createView(container)
    container.addView(currentView)

    screenUpdated()
  }

  fun goBack() {
    onCreateOptionsMenu = NO_MENU

    currentView.startAnimation(loadAnimation(this, R.anim.leak_canary_exit_backward))
    container.removeView(currentView)
    currentView.notifyScreenExiting()

    val latest = backstack.removeAt(backstack.size - 1)
    currentScreen = latest.screen
    currentView = currentScreen.createView(container)
    currentView.startAnimation(loadAnimation(this, R.anim.leak_canary_enter_backward))
    container.addView(currentView, 0)
    latest.restore(currentView)

    screenUpdated()
  }

  private fun screenUpdated() {
    invalidateOptionsMenu()
    if (SDK_INT >= 18) {
      actionBar?.run {
        val goBack = backstack.size > 0
        val indicator = if (goBack) 0 else android.R.drawable.ic_menu_close_clear_cancel
        setHomeAsUpIndicator(indicator)
      }
    }
    onNewScreen(currentScreen)
  }

  protected open fun onNewScreen(screen: Screen) {
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    onCreateOptionsMenu.invoke(menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean =
    when (item.itemId) {
      android.R.id.home -> {
        onBackPressed()
        true
      }
      else -> super.onOptionsItemSelected(item)
    }

  override fun onDestroy() {
    super.onDestroy()
    currentView.notifyScreenExiting()
  }

  companion object {
    val NO_MENU: ((Menu) -> Unit) = {}
  }
}
