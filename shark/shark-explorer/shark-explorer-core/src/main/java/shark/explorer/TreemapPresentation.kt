package shark.explorer

/**
 * A treemap laid out and labelled, holding everything needed to draw it and nothing that needs the
 * heap dump. Produced by [HeapDominatorTreemap.present], off the UI thread.
 */
class TreemapPresentation(
  /** Kept for hit testing, which is [TreemapLayoutResult.cellAt]. */
  val layout: TreemapLayoutResult<Long>,
  /** In the same order as [TreemapLayoutResult.cells], so back to front draw order. */
  val cells: List<PresentedCell<TreemapCell<Long>>>
) {

  /** See [TreemapLayoutResult.truncatedNodeCount]. */
  val truncatedNodeCount: Int get() = layout.truncatedNodeCount

  companion object {
    val EMPTY = TreemapPresentation(
      layout = TreemapLayoutResult(emptyList(), truncatedNodeCount = 0),
      cells = emptyList()
    )
  }
}

/**
 * The same tree as a [TreemapPresentation], laid out as rings instead. Produced by
 * [HeapDominatorTreemap.presentRadial].
 */
class RadialPresentation(
  /** Kept for hit testing, which is [RadialLayoutResult.cellAt]. */
  val layout: RadialLayoutResult<Long>,
  /** In the same order as [RadialLayoutResult.cells]. */
  val cells: List<PresentedCell<RadialCell<Long>>>
) {

  /** See [TreemapLayoutResult.truncatedNodeCount]. */
  val truncatedNodeCount: Int get() = layout.truncatedNodeCount
}

/**
 * One cell of a presentation: a laid out cell of some shape, plus what the heap dump had to be read
 * for. Generic in the shape so that a view keeps hold of the geometry it draws, while everything that
 * only needs [LayoutCell] works for either.
 */
class PresentedCell<out C : LayoutCell<Long>>(
  val cell: C,
  /** What to draw on the cell. */
  val label: String,
  /** How strongly the object is reachable, null for a [CellSubject.Group]. */
  val strength: ReachabilityStrength?
)
