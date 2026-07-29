package shark.explorer

/**
 * One cell of a laid out view of a tree: a rectangle in a treemap, an annular sector in the radial
 * view.
 *
 * The shape is all the two views disagree on. What a cell stands for is its [subject], so labels,
 * colours, what a click selects and what a double click zooms into are one implementation rather than
 * one per view.
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
   * Keeps the children of a subdivided node covering their whole share of its area, so that space a
   * node doesn't hand out to a child always means "this object's own bytes" rather than "children too
   * small or too many to draw". A group is not a tree node, so it can't be subdivided — [parent] is
   * there to say what it belongs to, and to tell one group from another.
   */
  data class Group<out N>(
    val parent: N,
    val nodeCount: Int
  ) : CellSubject<N>
}

/**
 * The nodes from just below the laid out root down to [subject], which is what zooming into a cell
 * follows: a cell several levels deep leaves a breadcrumb for every dominator on the way rather than
 * jumping straight to it. Empty for the root.
 *
 * A group isn't a node, so the path to one ends at the node whose children it stands for: zooming
 * into a group is zooming into what holds it, which is the only way to see what's in it.
 */
fun <N> List<CellSubject<N>>.nodePathTo(subject: CellSubject<N>): List<N> {
  val parentByNode = HashMap<N, N?>(size)
  forEach { if (it is CellSubject.Node) parentByNode[it.node] = it.parent }
  val path = mutableListOf<N>()
  var next: N? = when (subject) {
    is CellSubject.Node -> subject.node
    is CellSubject.Group -> subject.parent
  }
  // The root is the one node laid out without a parent, so the walk up ends there.
  while (next != null) {
    path += next
    next = parentByNode[next]
  }
  return path.asReversed().drop(1)
}
