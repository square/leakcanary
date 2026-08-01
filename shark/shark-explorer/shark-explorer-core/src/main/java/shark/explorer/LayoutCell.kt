package shark.explorer

/**
 * One cell of a laid out view of a tree: a rectangle in a treemap, an annular sector in the radial
 * view.
 *
 * The shape is all the two views disagree on. What a cell stands for is its [subject], so labels,
 * colours and where a click goes are one implementation rather than one per view.
 */
interface LayoutCell<out N> {
  val subject: CellSubject<N>

  /** 0 for the node the layout was rooted at. */
  val depth: Int

  /** What the cell's area is proportional to, e.g. a retained heap size in bytes. */
  val weight: Long
}

/** What a [LayoutCell] stands for. */
sealed interface CellSubject<out N> {

  /** One node of the tree. */
  data class Node<out N>(
    val node: N,
    /** The node this one is nested in, null for the node the layout was rooted at. */
    val parent: N?,
    /**
     * Where this node ranks among its parent's children, heaviest first, or 0 for the root.
     *
     * Stable as the viewport changes: a smaller viewport draws fewer children, but it draws the same
     * heaviest ones, so a rank never shifts. Which is what lets a colour scheme key off it.
     */
    val siblingIndex: Int
  ) : CellSubject<N>

  /**
   * The [nodeCount] children of [parent] that were left out of its subdivision, as one cell.
   *
   * A group is not a tree node, so it can't be subdivided — [parent] is there to say what it belongs
   * to, and to tell one group from another.
   */
  data class Group<out N>(
    val parent: N,
    val nodeCount: Int
  ) : CellSubject<N>

  /**
   * What [node] weighs on its own, as a cell nested inside it: its shallow size, for a dominator
   * tree.
   *
   * Without it a subdivided node's children would be scaled up to fill it, and area would only be
   * proportional to weight among siblings. With it, every rectangle of the view is its share of the
   * whole however deep it sits — and an object whose bytes are mostly its own, a bitmap being the one
   * that matters, is a solid block rather than an outline around its children.
   */
  data class Own<out N>(val node: N) : CellSubject<N>
}
