package org.leakcanary.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import org.leakcanary.screens.Destination.ClientAppsDestination

/**
 * Makes the BackStack state stream injectable in activity scoped view models. This
 * is a dumb hack as I couldn't figure out how to inject a view model into a view model.
 * This is doubly dumb as we need to ensure the backstack is created before
 * BackStackHolder.backStack is accessed.
 */
@ActivityRetainedScoped
class BackStackHolder @Inject constructor() {
  lateinit var backStackViewModel: BackStackViewModel
}

@InstallIn(ActivityRetainedComponent::class)
@Module
class BackStackModule {
  @Provides fun provideNavigator(holder: BackStackHolder): Navigator = holder.backStackViewModel
  @Provides fun provideAppBarTitle(holder: BackStackHolder): AppBarTitle = holder.backStackViewModel
}

interface Navigator {
  val currentScreenState: StateFlow<CurrentScreenState>

  fun goBack()

  fun goTo(destination: Destination)

  fun resetTo(destination: Destination)
}

inline fun <reified T : Destination> Navigator.filterDestination(): Flow<T> {
  return currentScreenState
    .map { it.destination }
    .filterIsInstance()
}

interface AppBarTitle {
  fun updateAppBarTitle(title: String)
}

// TODO This currently does not save UI state in the backstack.
@HiltViewModel
class BackStackViewModel @Inject constructor(
  private val savedStateHandle: SavedStateHandle,
  stateStream: BackStackHolder
) : ViewModel(), Navigator, AppBarTitle {

  private var destinationStack: List<Destination> =
    savedStateHandle[BACKSTACK_KEY] ?: arrayListOf(ClientAppsDestination)
    set(value) {
      field = value
      savedStateHandle[BACKSTACK_KEY] = ArrayList(value)
    }

  private val _currentScreenState = MutableStateFlow(destinationStack.asState(true))

  private val currentScreenTitle: String
    get() = _currentScreenState.value.destination.title

  private val _appBarTitle = MutableStateFlow(currentScreenTitle)

  override val currentScreenState = _currentScreenState.asStateFlow()

  val appBarTitle = _appBarTitle.asStateFlow()

  init {
    stateStream.backStackViewModel = this
  }

  override fun goBack() {
    check(_currentScreenState.value.canGoBack) {
      "Backstack cannot go further back."
    }
    navigate(destinationStack.dropLast(1), forward = false)
  }

  override fun goTo(destination: Destination) {
    navigate(destinationStack + destination, forward = true)
  }

  private fun navigate(newDestinationStack: List<Destination>, forward: Boolean) {
    destinationStack = newDestinationStack
    _currentScreenState.value = newDestinationStack.asState(forward)
    _appBarTitle.value = currentScreenTitle
  }

  companion object {
    private const val BACKSTACK_KEY = "backstack"
  }

  override fun resetTo(destination: Destination) {
    navigate(listOf(destination), forward = false)
  }

  override fun updateAppBarTitle(title: String) {
    _appBarTitle.value = title
  }
}

private fun List<Destination>.asState(forward: Boolean) =
  CurrentScreenState(destination = last(), canGoBack = size > 1, forward)

data class CurrentScreenState(
  val destination: Destination,
  val canGoBack: Boolean,
  val forward: Boolean
)
