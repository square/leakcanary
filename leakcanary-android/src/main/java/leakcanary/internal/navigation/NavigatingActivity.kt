package leakcanary.internal.navigation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils.loadAnimation
import com.squareup.leakcanary.R

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
      currentScreen = if (intent.hasExtra("screens")) {
        @Suppress("UNCHECKED_CAST")
        val screens = intent.getSerializableExtra("screens") as List<Screen>
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
    updateActionBar()
  }

  override fun onNewIntent(intent: Intent) {
    if (intent.hasExtra("screens")) {
      @Suppress("UNCHECKED_CAST")
      val screens = intent.getSerializableExtra("screens") as List<Screen>
      goTo(intent.getSerializableExtra("screen") as Screen)
      backstack.clear()
      screens.dropLast(1)
          .forEach { screen ->
            backstack.add(BackstackFrame(screen))
          }
    }
  }

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

  fun goTo(screen: Screen) {
    onCreateOptionsMenu = NO_MENU

    currentView.startAnimation(loadAnimation(this, R.anim.leak_canary_exit_forward))
    container.removeView(currentView)
    val backstackFrame = BackstackFrame(currentScreen, currentView)
    backstack.add(backstackFrame)

    currentScreen = screen
    currentView = currentScreen.createView(container)
    currentView.startAnimation(loadAnimation(this, R.anim.leak_canary_enter_forward))
    container.addView(currentView)

    updateActionBar()
  }

  fun goBack() {
    onCreateOptionsMenu = NO_MENU

    currentView.startAnimation(loadAnimation(this, R.anim.leak_canary_exit_backward))
    container.removeView(currentView)

    val latest = backstack.removeAt(backstack.size - 1)
    currentScreen = latest.screen
    currentView = currentScreen.createView(container)
    currentView.startAnimation(loadAnimation(this, R.anim.leak_canary_enter_backward))
    container.addView(currentView, 0)
    latest.restore(currentView)

    updateActionBar()
  }

  private fun updateActionBar() {
    invalidateOptionsMenu()
    val actionBar = actionBar
        ?: // https://github.com/square/leakcanary/issues/967
        return
    actionBar.setDisplayHomeAsUpEnabled(backstack.size > 0)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    onCreateOptionsMenu.invoke(menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean =
    when (item.itemId) {
      android.R.id.home -> {
        goBack()
        true
      }
      else -> super.onOptionsItemSelected(item)
    }

  companion object {
    val NO_MENU: ((Menu) -> Unit) = {}
  }

}
