package shark.explorer

/**
 * The node a treemap is currently rooted at, plus the path of nodes zoomed through to reach it.
 *
 * Immutable so it can be held as UI state, and in this module rather than in the UI so that
 * navigation is unit testable.
 */
data class TreemapNavigation<N>(val path: List<N>) {

  constructor(root: N) : this(listOf(root))

  init {
    require(path.isNotEmpty()) { "A navigation path always contains at least the root" }
  }

  /** The node the treemap is rooted at. */
  val current: N get() = path.last()

  val canZoomOut: Boolean get() = path.size > 1

  /**
   * Roots the treemap at [node].
   *
   * A no op for [current], and a zoom back out when [node] is already on the path, so that clicking
   * a breadcrumb and clicking a rectangle can both go through here.
   */
  fun zoomInto(node: N): TreemapNavigation<N> {
    val index = path.indexOf(node)
    return when {
      index == path.lastIndex -> this
      index != -1 -> TreemapNavigation(path.take(index + 1))
      else -> TreemapNavigation(path + node)
    }
  }

  /**
   * Roots the treemap at the last of [nodes], recording the ones on the way as path entries.
   *
   * Zooming into a rectangle nested several levels deep should leave breadcrumbs for every node
   * between it and the root, not jump straight to it, so the caller passes the whole chain.
   */
  fun zoomInto(nodes: List<N>): TreemapNavigation<N> = nodes.fold(this) { path, node ->
    path.zoomInto(node)
  }

  fun zoomOut(): TreemapNavigation<N> =
    if (canZoomOut) TreemapNavigation(path.dropLast(1)) else this

  /**
   * The longest prefix of this path that [isStillThere] accepts, keeping the root whatever it says.
   *
   * Following a weaker reference strength rebuilds the tree, and an object that was zoomed into may
   * not be a node of the new one, or may no longer be dominated by the node above it on the path.
   */
  fun retainingWhere(isStillThere: (N) -> Boolean): TreemapNavigation<N> {
    val retained = path.drop(1).takeWhile(isStillThere)
    return if (retained.size == path.size - 1) this else TreemapNavigation(listOf(path[0]) + retained)
  }
}
