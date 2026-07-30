package shark.explorer

/**
 * What the explorer is showing, which is what the breadcrumbs say and what the back arrow walks through.
 *
 * The treemap is one screen among several, and the others are reached from it: the paths that hold an
 * object, every object of the heap dump as a list, the objects starred so far. So each of them keeps the
 * [treeNavigation] it was opened from, which is what makes the breadcrumbs a trail rather than a label —
 * every crumb but the last leads back to the map, wherever the screen went.
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
   * so that a screen returned to by the back arrow describes what it described before — a screen the
   * breadcrumbs name and a panel describing something else is a window that has to be read twice.
   */
  val describedNode: Long

  /** What the breadcrumbs say past the tree's own path, or null on the tree itself. */
  val trailingCrumb: String?

  /** The same screen, with the treemap somewhere else. */
  fun withTreeNavigation(navigation: TreemapNavigation<Long>): ExplorerScreen

  /** The dominator tree, drawn as a treemap or as rings. */
  data class Tree(
    override val treeNavigation: TreemapNavigation<Long>,
    /** The node the map is rooted at unless a cell inside it was picked out. */
    override val describedNode: Long = treeNavigation.current
  ) : ExplorerScreen {

    override val trailingCrumb: String? get() = null

    override fun withTreeNavigation(navigation: TreemapNavigation<Long>) = copy(treeNavigation = navigation)
  }

  /** Every way one object is held below its dominator. See [IndependentPaths]. */
  data class Paths(
    override val treeNavigation: TreemapNavigation<Long>,
    val objectId: Long
  ) : ExplorerScreen {

    /** The object the paths hold, which is what they're worth reading beside. */
    override val describedNode: Long get() = objectId

    override val trailingCrumb: String get() = PATHS_CRUMB

    override fun withTreeNavigation(navigation: TreemapNavigation<Long>) = copy(treeNavigation = navigation)
  }

  /** Every object of the heap dump as a list, filtered. See [HeapDominatorTreemap.listObjects]. */
  data class Objects(
    override val treeNavigation: TreemapNavigation<Long>,
    val filter: ObjectListFilter,
    /** Whatever was being described when the list was opened: leaving the map isn't a move on it. */
    override val describedNode: Long = treeNavigation.current
  ) : ExplorerScreen {

    override val trailingCrumb: String get() = OBJECTS_CRUMB

    override fun withTreeNavigation(navigation: TreemapNavigation<Long>) = copy(treeNavigation = navigation)
  }

  /** The objects starred so far, kept so that two of them can be compared. */
  data class Starred(
    override val treeNavigation: TreemapNavigation<Long>,
    /** Whatever was being described when the starred ones were opened, as on [Objects]. */
    override val describedNode: Long = treeNavigation.current
  ) : ExplorerScreen {

    override val trailingCrumb: String get() = STARRED_CRUMB

    override fun withTreeNavigation(navigation: TreemapNavigation<Long>) = copy(treeNavigation = navigation)
  }

  companion object {
    const val PATHS_CRUMB = "Paths from the dominator"
    const val OBJECTS_CRUMB = "All objects"
    const val STARRED_CRUMB = "Starred"
  }
}
