package leakcanary.internal.navigation

import android.view.View
import android.view.ViewGroup
import java.io.Serializable

/**
 * Replaces Fragments, MVP, MVC, MVVM, MVMVMVM and everything else in just one tiny class.
 * A screen is a location to go to, and it can build a view to display.
 */
internal abstract class Screen : Serializable {

  abstract fun createView(container: ViewGroup): View
}