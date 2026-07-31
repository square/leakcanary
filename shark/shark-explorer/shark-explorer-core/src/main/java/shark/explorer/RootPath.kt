package shark.explorer

/**
 * The shortest way a GC root reaches one object, with the objects that dominate it marked. See
 * [HeapDominatorTreemap.rootPathTo].
 *
 * What a treemap can't say on its own: a rectangle says which object the tree attributes these bytes to,
 * and this says which of the heap dump's own references had to be followed to get to them. Shortest
 * counted in steps, so it's the plainest way the object is held rather than one of the ways round —
 * [IndependentPaths] is what spells out the others.
 */
data class RootPath(
  /**
   * Which kind of GC root the chain starts at, or that the object is uncollected garbage. Null when
   * nothing the tree was built from reaches it, which is when [steps] is empty.
   */
  val gcRootLabel: String?,
  /** From the GC rooted object down to the object itself, which is the last step. */
  val steps: List<RootPathStep>,
  /** How many steps between the GC root and [steps] were left out to keep the chain readable. */
  val hiddenStepCount: Int
) {
  companion object {
    /** No chain: nothing the tree was built from reaches the object. */
    val NONE = RootPath(gcRootLabel = null, steps = emptyList(), hiddenStepCount = 0)
  }
}

/**
 * One object along a [RootPath].
 *
 * [isDominator] is what makes the chain more than a list of holders: every path from a GC root to the
 * object goes through each of its dominators, so a marked step is one that releasing would free the
 * object, and the rest are only on the way to it.
 */
data class RootPathStep(
  val step: PathStep,
  val isDominator: Boolean
)
