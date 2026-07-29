package shark.explorer

/**
 * A treemap laid out and labelled, holding everything needed to draw it and nothing that needs the
 * heap dump. Produced by [HeapDominatorTreemap.present], off the UI thread.
 */
class TreemapPresentation(
  /** Kept for hit testing, which is [TreemapLayoutResult.cellAt]. */
  val layout: TreemapLayoutResult<Long>,
  /** In the same order as [TreemapLayoutResult.cells], so back to front draw order. */
  val cells: List<PresentedCell>
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

/** One rectangle of a [TreemapPresentation]. */
class PresentedCell(
  val cell: TreemapCell<Long>,
  /** What to draw on the rectangle. */
  val label: String,
  /** How strongly the object is reachable, null for a [TreemapCell.Group]. */
  val strength: ReachabilityStrength?
)
