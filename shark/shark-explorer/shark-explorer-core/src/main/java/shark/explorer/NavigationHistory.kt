package shark.explorer

/**
 * Where a view has been, so that a back arrow undoes a move and a forward arrow redoes it.
 *
 * Zooming in and out already walks up and down one path, but the panel leads sideways: clicking a
 * dominator or a step of a path jumps to wherever the treemap draws that object, and the way back from
 * there isn't up. This remembers the moves themselves.
 *
 * Immutable so it can be held as UI state, and in this module rather than in the UI so that it's unit
 * testable, like [TreemapNavigation].
 */
data class NavigationHistory<N>(
  /** Oldest first. Always holds at least one entry, the one [current] returns. */
  val entries: List<TreemapNavigation<N>>,
  /** Which entry [current] returns, counted from the oldest. */
  val index: Int
) {

  constructor(navigation: TreemapNavigation<N>) : this(listOf(navigation), 0)

  init {
    require(index in entries.indices) {
      "Index $index is not one of the ${entries.size} entries of this history"
    }
  }

  val current: TreemapNavigation<N> get() = entries[index]

  val canGoBack: Boolean get() = index > 0

  val canGoForward: Boolean get() = index < entries.lastIndex

  /**
   * Records [navigation] as where the view is now, which is what makes it the place the back arrow
   * returns to next.
   *
   * Whatever the forward arrow led to is dropped, the way a browser drops it: having gone somewhere else,
   * redoing the move that was undone isn't a move any more. A no op for the current entry, so that
   * clicking the rectangle already being shown doesn't fill the history with it.
   */
  fun goTo(navigation: TreemapNavigation<N>): NavigationHistory<N> = when (navigation) {
    current -> this
    else -> NavigationHistory(entries.take(index + 1) + navigation, index + 1)
  }

  /**
   * Replaces where the view is now without recording a move.
   *
   * For a path that came back shorter than it was asked for: the view settling on what it could actually
   * show isn't somewhere the back arrow should return to.
   */
  fun replacingCurrent(navigation: TreemapNavigation<N>): NavigationHistory<N> = when (navigation) {
    current -> this
    else -> NavigationHistory(entries.toMutableList().also { it[index] = navigation }, index)
  }

  fun goBack(): NavigationHistory<N> = if (canGoBack) copy(index = index - 1) else this

  fun goForward(): NavigationHistory<N> = if (canGoForward) copy(index = index + 1) else this
}
