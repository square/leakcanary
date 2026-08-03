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
 * The same tree as a [TreemapPresentation], laid out as a stack of rows instead. Produced by
 * [HeapDominatorTreemap.presentStack].
 */
class StackPresentation(
  /** Kept for hit testing, which is [StackLayoutResult.cellAt], and for how tall the stack came out. */
  val layout: StackLayoutResult<Long>,
  /** In the same order as [StackLayoutResult.cells]. */
  val cells: List<PresentedCell<StackCell<Long>>>
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
  val content: CellContent
) {
  /** How firmly whatever the cell stands for is held. */
  val strength: ReachabilityStrength get() = when (val content = content) {
    is CellContent.Object -> content.strength
    is CellContent.ObjectGroup -> content.strength
    is CellContent.Leftover -> content.strength
  }
}

/**
 * What a presented cell stands for, which is what decides how it's drawn: two of the three aren't an
 * object of the heap dump, and a view that drew them like one would be lying about the heap.
 */
sealed interface CellContent {

  /** One object of the heap dump. */
  data class Object(
    val strength: ReachabilityStrength,
    /**
     * Whether the object is an `android.graphics.Bitmap`, which is what makes a cell one an image can
     * be drawn on. Says nothing about whether the heap dump has that image: see
     * [HeapDominatorTreemap.bitmapImages], which is the read that finds out.
     */
    val isBitmap: Boolean = false
  ) : CellContent

  /**
   * A pile of objects as one cell: half of the heap dump, or every instance of one class the root
   * dominates. See [HeapDominatorTreemap.groupOrNull].
   */
  data class ObjectGroup(
    val kind: ObjectGroupKind,
    /** How firmly the objects in it are held, which they all are the same way. */
    val strength: ReachabilityStrength,
    val objectCount: Int
  ) : CellContent

  /** The children of a node that its subdivision had no room for. See [CellSubject.Group]. */
  data class Leftover(
    /**
     * How firmly the node holding them is held, standing in for their own: they're a pile of objects with
     * nothing else in common, and one of them could be held more weakly than the rest.
     */
    val strength: ReachabilityStrength
  ) : CellContent
}
