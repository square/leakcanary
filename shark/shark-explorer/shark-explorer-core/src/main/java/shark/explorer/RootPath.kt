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
  val steps: List<RootPathStep>
) {
  companion object {
    /** No chain: nothing the tree was built from reaches the object. */
    val NONE = RootPath(gcRootLabel = null, steps = emptyList())
  }
}

/**
 * The part of this chain a map rooted at [rootNodeId] is showing: from the object drawn as one of that
 * root's own rectangles down to the object the chain leads to.
 *
 * Which is all a chain glanced at while the pointer moves has to say. The rectangle under the pointer is
 * somewhere inside one of the blocks the map is divided into, and which block that is answers what holds it
 * *here*; the steps above are how that block itself is held, which is what going there would say instead.
 *
 * The first dominator below the root, because every path from a GC root to the object goes through every one
 * of its dominators: the dominator nearest the root is the rectangle the map draws it inside of. A step only
 * on the way is not one of those, so it is cut along with the rest. Just the object itself when nothing below
 * the root dominates it, which is what pointing at one of the root's own rectangles is.
 *
 * The whole chain when [rootNodeId] is nowhere on it, which is a map rooted at a pile of objects: a pile is
 * no object of the heap dump, so it says nothing about where the chain reaches the screen.
 */
fun RootPath.stepsBelow(rootNodeId: Long): List<RootPathStep> {
  if (steps.isEmpty()) {
    return steps
  }
  val rootIndex = steps.indexOfFirst { it.step.objectId == rootNodeId }
  if (rootIndex == -1 && rootNodeId != HeapDominatorTreemap.ROOT_OBJECT_ID) {
    return steps
  }
  val fromIndex = (rootIndex + 1 until steps.size).firstOrNull { steps[it].isDominator }
    ?: steps.lastIndex
  return steps.subList(fromIndex, steps.size)
}

/**
 * The part of this chain below [objectId], or null when no step of it is that object.
 *
 * What the chain to the rectangle under the pointer has to add to the chain already on screen: the object
 * the window is describing is on both of them, so the steps below it are the whole of the difference, and
 * they read as the chain running on rather than as a second chain of their own.
 */
fun RootPath.stepsAfter(objectId: Long): List<RootPathStep>? {
  val index = steps.indexOfFirst { it.step.objectId == objectId }
  return if (index == -1) null else steps.subList(index + 1, steps.size)
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
