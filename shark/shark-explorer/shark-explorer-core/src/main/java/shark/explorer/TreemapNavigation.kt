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

  fun zoomOut(): TreemapNavigation<N> =
    if (canZoomOut) TreemapNavigation(path.dropLast(1)) else this
}
