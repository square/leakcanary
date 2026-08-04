package shark.explorer

/**
 * A tree of one heap dump the explorer can draw and navigate.
 *
 * There are two: [HeapDominatorTreemap], which is what retains what read from the GC roots down, and
 * [ReverseDominatorTree], which is the same domination read from the classes up. Two trees rather than two
 * shapes of one, because a node of the first is an object of the heap dump and a node of the second is a pile
 * of them at one point of a column — so what a cell says, and what a click on it can lead to, is different.
 *
 * What they have in common is everything a laid out view needs, which is this: what a node weighs, what to
 * call it, where it is drawn, and whether it is in the tree at all.
 *
 * **They share their root**, [HeapDominatorTreemap.ROOT_OBJECT_ID], because the whole heap dump is the whole
 * heap dump in either reading. Which is what lets the UI keep one navigation path for both: a path at the
 * root is a path of either tree, and a path zoomed into one of them is dropped by the other's [contains].
 *
 * Reads the heap dump, so not from the UI thread. See [HeapExplorer].
 */
interface HeapTree : TreemapTree<Long> {

  /** Whether [nodeId] is a node of this tree. */
  operator fun contains(nodeId: Long): Boolean

  /**
   * A short name for [nodeId], to draw on its cell.
   *
   * Cheap enough to call for every visible cell, unlike whatever this tree summarises one with.
   */
  fun label(nodeId: Long): String

  /**
   * The nodes to zoom through so that [nodeId] is drawn, the root first.
   *
   * Stops at the last node that has children: zooming into a node with nothing under it would draw an empty
   * view, so the caller ends up looking at what holds it with it selected inside.
   */
  fun pathToOpen(nodeId: Long): List<Long>

  /**
   * Reads a label and a strength for every one of [cells]: everything the UI needs to draw a laid out shape
   * of this tree without touching the heap dump itself.
   *
   * What shape they are is no business of a tree, which is why there is one of these rather than one per
   * shape — a `TreemapCell`, a `RadialCell` and a `StackCell` are all a [CellSubject] with geometry, and a
   * name is read off the subject. Pairing a layout with a tree is [TreemapPresentation.of] and its two
   * siblings, so a fourth shape needs nothing here.
   */
  fun <C : LayoutCell<Long>> present(cells: List<C>): List<PresentedCell<C>>
}
