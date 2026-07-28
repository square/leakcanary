package leakcanary.internal.navigation

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils.loadAnimation
import android.widget.Toolbar
import com.squareup.leakcanary.core.R

/**
 * A simple backstack navigating activity
 *
 * The options menu is hosted by a [Toolbar] that this activity owns, rather than by the window
 * decor action bar, which is disabled by the LeakCanary theme. See the comment in
 * leak_canary_themes.xml for why.
 */
internal abstract class NavigatingActivity : Activity() {

  private lateinit var backstack: ArrayList<BackstackFrame>
  private lateinit var currentScreen: Screen

  private lateinit var container: ViewGroup
  private lateinit var currentView: View
  private var toolbar: Toolbar? = null

  private val homeAsUpIndicator: Drawable? by lazy {
    val typedArray = theme.obtainStyledAttributes(intArrayOf(android.R.attr.homeAsUpIndicator))
    try {
      typedArray.getDrawable(0)
    } finally {
      typedArray.recycle()
    }
  }

  var onCreateOptionsMenu = NO_MENU
    set(value) {
      field = value
      updateToolbarMenu()
    }

  fun installNavigation(
    savedInstanceState: Bundle?,
    container: ViewGroup,
    toolbar: Toolbar
  ) {
    this.container = container
    this.toolbar = toolbar
    toolbar.title = title
    toolbar.setNavigationOnClickListener { onBackPressed() }

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
    updateToolbarMenu()
    toolbar?.run {
      val goBack = backstack.size > 0
      if (goBack) {
        navigationIcon = homeAsUpIndicator
        setNavigationContentDescription(R.string.leak_canary_navigate_up)
      } else {
        setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
        setNavigationContentDescription(R.string.leak_canary_close)
      }
    }
    onNewScreen(currentScreen)
  }

  private fun updateToolbarMenu() {
    toolbar?.run {
      menu.clear()
      onCreateOptionsMenu.invoke(menu)
    }
  }

  protected open fun onNewScreen(screen: Screen) {
  }

  override fun onTitleChanged(
    title: CharSequence?,
    color: Int
  ) {
    super.onTitleChanged(title, color)
    toolbar?.title = title
  }

  override fun onDestroy() {
    super.onDestroy()
    currentView.notifyScreenExiting()
  }

  companion object {
    val NO_MENU: ((Menu) -> Unit) = {}
  }
}
