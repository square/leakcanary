package shark.explorer

/**
 * One tab of a window: where it is now, and everywhere it has been.
 *
 * A history each rather than one for the window, because that is what a tab is for — parking a place and
 * going somewhere else without losing the way back to it. A window wide history would make the back arrow
 * walk out of the tab you are looking at.
 */
data class Tab(
  /**
   * Tells this tab from the others as tabs are opened and closed.
   *
   * Never reused within a window, so a tab closed and another opened are two tabs rather than one that
   * changed its mind — which is what an index would have made them.
   */
  val id: Int,
  val history: NavigationHistory<Place>
) {

  constructor(id: Int, place: Place) : this(id, NavigationHistory(place))

  /** Where this tab is, which is what it draws and what it is called. */
  val place: Place get() = history.current
}

/**
 * The tabs of one window and which of them is on screen.
 *
 * Every tab is closeable, including the last one: closing it leaves a window with the bar above the tabs
 * and nothing under it, which is one click from a tab again and doesn't throw away the heap dump the
 * window has spent seconds reading. A window is still one heap dump — see `notes/decisions.md` — so
 * closing tabs is never closing that.
 *
 * Immutable, and in this module rather than in the UI so that opening, closing and navigating stay unit
 * testable, like [NavigationHistory] and [Place].
 */
data class Tabs(
  /** Left to right, as the strip draws them. */
  val tabs: List<Tab>,
  /** Which tab is on screen, or null once the last one has been closed. */
  val selectedId: Int?,
  /** The id the next tab opened takes. Counted up rather than reused, see [Tab.id]. */
  val nextId: Int
) {

  init {
    require(selectedId == null || tabs.any { it.id == selectedId }) {
      "Tab $selectedId is selected and is not one of the ${tabs.size} tabs open"
    }
    require(tabs.distinctBy { it.id }.size == tabs.size) {
      "Two tabs share an id: ${tabs.map { it.id }}"
    }
  }

  /** The tab on screen, or null when the last one has been closed. */
  val selected: Tab? get() = tabs.firstOrNull { it.id == selectedId }

  /** Where the window is, or null when no tab is open. */
  val place: Place? get() = selected?.place

  val canGoBack: Boolean get() = selected?.history?.canGoBack == true

  val canGoForward: Boolean get() = selected?.history?.canGoForward == true

  /**
   * Opens [place] in a tab of its own, beside the one it was opened from.
   *
   * Beside rather than at the end, the way a browser does it, so that the tabs opened while reading one
   * object stay next to that object instead of collecting at the far end of the strip.
   *
   * [inBackground] is what a middle click and a modifier click do: the tab is there when you want it,
   * having not moved you off what you were reading. The buttons on the bar open in front instead, since
   * clicking one is asking to be somewhere else.
   */
  fun open(
    place: Place,
    inBackground: Boolean = false
  ): Tabs {
    val opened = Tab(nextId, place)
    val openedAfter = tabs.indexOfFirst { it.id == selectedId }
    val insertAt = if (openedAfter == -1) tabs.size else openedAfter + 1
    return Tabs(
      tabs = tabs.take(insertAt) + opened + tabs.drop(insertAt),
      selectedId = if (inBackground && selectedId != null) selectedId else opened.id,
      nextId = nextId + 1
    )
  }

  /**
   * Closes the tab [id], selecting the one after it, or the one before it when it was the last on the
   * strip.
   *
   * After rather than before, because a tab opened from another is inserted after it: closing what you
   * opened puts you back on what you opened it from.
   */
  fun close(id: Int): Tabs {
    val index = tabs.indexOfFirst { it.id == id }
    if (index == -1) {
      return this
    }
    val remaining = tabs.filterNot { it.id == id }
    val selected = when {
      // Closing a tab that isn't the one on screen leaves the one on screen where it is.
      id != selectedId -> selectedId
      else -> remaining.getOrNull(index)?.id ?: remaining.lastOrNull()?.id
    }
    return copy(tabs = remaining, selectedId = selected)
  }

  fun select(id: Int): Tabs = if (tabs.any { it.id == id }) copy(selectedId = id) else this

  /**
   * Goes to [place] in the tab on screen, which is what a click on an object does.
   *
   * A window with no tab open gets one, so that a click always lands somewhere: the alternative is a
   * click that does nothing at all, which reads as the window having stopped working.
   */
  fun goTo(place: Place): Tabs = mapSelected(ifNoTabIsOpen = { open(place) }) { it.goTo(place) }

  /**
   * Replaces where the tab on screen is without recording a move, for a filter typed into the object list
   * and a leak unfolded. See [NavigationHistory.replacingCurrent].
   */
  fun replacingCurrent(place: Place): Tabs =
    mapSelected(ifNoTabIsOpen = { open(place) }) { it.replacingCurrent(place) }

  fun goBack(): Tabs = mapSelected { it.goBack() }

  fun goForward(): Tabs = mapSelected { it.goForward() }

  private fun mapSelected(
    ifNoTabIsOpen: () -> Tabs = { this },
    walk: (NavigationHistory<Place>) -> NavigationHistory<Place>
  ): Tabs {
    val selected = selected ?: return ifNoTabIsOpen()
    return copy(
      tabs = tabs.map { tab ->
        if (tab.id == selected.id) tab.copy(history = walk(tab.history)) else tab
      }
    )
  }

  companion object {
    /** A window's first tab, which is the heap dump as a whole. */
    fun opening(place: Place): Tabs = Tabs(
      tabs = listOf(Tab(FIRST_TAB_ID, place)),
      selectedId = FIRST_TAB_ID,
      nextId = FIRST_TAB_ID + 1
    )

    private const val FIRST_TAB_ID = 0
  }
}
