package shark.dive

/**
 * Where a view has been, so that a back arrow undoes a move and a forward arrow redoes it.
 *
 * Everything Shark Dive does is a move sideways: clicking a dominator or a step of a path shows that
 * object instead, and opening the object list or the starred ones leaves the map altogether. So there is
 * no up to walk, and the moves themselves are what has to be remembered.
 *
 * One of these per [Tab], not per window — see [Tabs] for why.
 *
 * Immutable so it can be held as UI state, and in this module rather than in the UI so that it's unit
 * testable, like [Place] and [Tabs].
 */
data class NavigationHistory<T>(
  /** Oldest first. Always holds at least one entry, the one [current] returns. */
  val entries: List<T>,
  /** Which entry [current] returns, counted from the oldest. */
  val index: Int
) {

  constructor(entry: T) : this(listOf(entry), 0)

  init {
    require(index in entries.indices) {
      "Index $index is not one of the ${entries.size} entries of this history"
    }
  }

  val current: T get() = entries[index]

  val canGoBack: Boolean get() = index > 0

  val canGoForward: Boolean get() = index < entries.lastIndex

  /**
   * Everywhere this has been before [current], the most recent first.
   *
   * In that order because it is the order the back arrow visits them in, which is what a list of them is
   * read as: the first entry is one click back, the second is two.
   */
  val backEntries: List<T> get() = entries.take(index).asReversed()

  /** And everywhere it went after, the nearest first, for the same reason. */
  val forwardEntries: List<T> get() = entries.drop(index + 1)

  /**
   * Goes back [steps] moves at once, for a click on the third entry of a back list rather than three clicks
   * on the arrow.
   *
   * Clamped rather than checked, because the list a click came from and the history it came from are the
   * same value one recomposition apart.
   */
  fun goBack(steps: Int): NavigationHistory<T> = copy(index = (index - steps).coerceAtLeast(0))

  /** And forward, the same way. */
  fun goForward(steps: Int): NavigationHistory<T> =
    copy(index = (index + steps).coerceAtMost(entries.lastIndex))

  /**
   * Records [entry] as where the view is now, which is what makes it the place the back arrow returns to
   * next.
   *
   * Whatever the forward arrow led to is dropped, the way a browser drops it: having gone somewhere else,
   * redoing the move that was undone isn't a move any more. A no op for the current entry, so that
   * clicking the rectangle already being shown doesn't fill the history with it.
   */
  fun goTo(entry: T): NavigationHistory<T> = when (entry) {
    current -> this
    else -> NavigationHistory(entries.take(index + 1) + entry, index + 1)
  }

  /**
   * Replaces where the view is now without recording a move.
   *
   * For a path that came back shorter than it was asked for, and for a filter typed into the object list:
   * the view settling on what it could actually show, or on what was asked of it a keystroke ago, isn't
   * somewhere the back arrow should return to.
   */
  fun replacingCurrent(entry: T): NavigationHistory<T> = when (entry) {
    current -> this
    else -> NavigationHistory(entries.toMutableList().also { it[index] = entry }, index)
  }

  fun goBack(): NavigationHistory<T> = if (canGoBack) copy(index = index - 1) else this

  fun goForward(): NavigationHistory<T> = if (canGoForward) copy(index = index + 1) else this
}
