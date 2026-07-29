package shark.explorer

/**
 * The ways an object is held, each spelled out from a GC root down to the object itself. See
 * [HeapDominatorTreemap.holdingPathsTo].
 *
 * This is what the dominator tree can't say. A dominator is the one object that would free this one,
 * and when there isn't one — two holders whose paths meet only at the root — the tree puts the bytes
 * under the root and stops there. The paths are what's actually holding it: usually a piece of UI or a
 * running job on one, and a cache on another.
 */
data class HoldingPaths(
  /** Shortest first, in the order the paths were found. */
  val paths: List<HoldingPath>,
  /**
   * The object deepest down that every path in [paths] goes through, other than the object itself:
   * whatever else holds it, it stays for as long as this one does. Null when the paths share nothing,
   * which is exactly when the root ends up dominating the object.
   */
  val commonHolderObjectId: Long?,
  /** Short name of [commonHolderObjectId], null along with it. */
  val commonHolderLabel: String?,
  /** How many more ways the object is held than [paths] spells out. */
  val hiddenPathCount: Int
)

/** One way an object is held: a chain of references from a GC root down to it. See [HoldingPaths]. */
data class HoldingPath(
  /** Which kind of GC root the chain starts at. */
  val gcRootLabel: String,
  /** From the GC rooted object down to the held object, which is the last step. */
  val steps: List<PathStep>,
  /** How many steps between the GC root and [steps], left out to keep the chain readable. */
  val hiddenStepCount: Int
)

/** One object along a [HoldingPath], and the reference that reaches it. */
data class PathStep(
  val objectId: Long,
  /** Short name, as drawn on a rectangle. */
  val label: String,
  /**
   * The field of the step before it that points at it, `[3]` for an array element, null for the first
   * step, which a GC root points at.
   */
  val referenceName: String?,
  /**
   * How many of the paths shown go through this object. More than one means the paths join here, and
   * as many as there are paths means nothing frees the object while this one is alive.
   */
  val pathCount: Int,
  /** Whether the object is in the tree and can therefore be inspected. */
  val isInspectable: Boolean
)
