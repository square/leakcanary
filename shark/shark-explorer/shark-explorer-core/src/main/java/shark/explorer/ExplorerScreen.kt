package shark.explorer

/**
 * What the explorer is showing, which is what the back arrow walks through.
 *
 * The treemap is one screen among several, and the others are reached from it: every object of the heap dump
 * as a list, the objects starred so far. So each of them keeps the [treeNavigation] it was opened from, which
 * is what makes going back to the map going back to where it was rather than to the top of the tree.
 *
 * Immutable, and in this module rather than in the UI so that navigation stays unit testable. See
 * [NavigationHistory].
 */
sealed interface ExplorerScreen {

  /** Where the treemap was when this screen was opened. */
  val treeNavigation: TreemapNavigation<Long>

  /**
   * The object the details panel describes here, which is what the move that led here was about.
   *
   * Not the node the map is rooted at: zooming into an object that dominates nothing would draw an empty
   * view, so opening one leaves the map on what holds it with the object selected inside. Kept per screen
   * so that a screen returned to by the back arrow describes what it described before: a window whose
   * panes describe something other than what it is showing has to be read twice.
   */
  val describedNode: Long

  /** The same screen, with the treemap somewhere else. */
  fun withTreeNavigation(navigation: TreemapNavigation<Long>): ExplorerScreen

  /** The dominator tree, drawn as a treemap or as rings. */
  data class Tree(
    override val treeNavigation: TreemapNavigation<Long>,
    /** The node the map is rooted at unless a cell inside it was picked out. */
    override val describedNode: Long = treeNavigation.current
  ) : ExplorerScreen {

    override fun withTreeNavigation(navigation: TreemapNavigation<Long>) = copy(treeNavigation = navigation)
  }

  /** Every object of the heap dump as a list, filtered. See [HeapDominatorTreemap.listObjects]. */
  data class Objects(
    override val treeNavigation: TreemapNavigation<Long>,
    val filter: ObjectListFilter,
    /** Whatever was being described when the list was opened: leaving the map isn't a move on it. */
    override val describedNode: Long = treeNavigation.current
  ) : ExplorerScreen {

    override fun withTreeNavigation(navigation: TreemapNavigation<Long>) = copy(treeNavigation = navigation)
  }

  /** Every leaking object of the heap dump, gathered into leaks. See [HeapDominatorTreemap.findLeaks]. */
  data class Leaks(
    override val treeNavigation: TreemapNavigation<Long>,
    /**
     * Which leaks have been unfolded to show the objects in them, by [LeakGroup.signature] and which section
     * the group is in: a leak of one class held two ways is two groups with one title.
     */
    val expandedGroups: Set<String> = emptySet(),
    /** Whatever was being described when the leaks were opened, as on [Objects]. */
    override val describedNode: Long = treeNavigation.current
  ) : ExplorerScreen {

    override fun withTreeNavigation(navigation: TreemapNavigation<Long>) = copy(treeNavigation = navigation)
  }

  /** The objects starred so far, kept so that two of them can be compared. */
  data class Starred(
    override val treeNavigation: TreemapNavigation<Long>,
    /** Whatever was being described when the starred ones were opened, as on [Objects]. */
    override val describedNode: Long = treeNavigation.current
  ) : ExplorerScreen {

    override fun withTreeNavigation(navigation: TreemapNavigation<Long>) = copy(treeNavigation = navigation)
  }

  companion object {
    /** What the button leading to the list of every object says. */
    const val OBJECTS_LABEL = "All objects"

    /** And the one leading to the leaks, beside it. */
    const val LEAKS_LABEL = "Leaks"
  }
}
