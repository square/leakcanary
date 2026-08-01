package shark.explorer

import androidx.collection.IntSet

/**
 * The walk from one object out to the nearest of [treeRootIndexes], which is the shortest way a GC root
 * reaches it: breadth first over the referrers, so the first root it reaches is the closest one there is.
 *
 * Object indexes rather than ids throughout, because that is what a [ReferrerIndex] walks in and what the
 * arrays here are indexed by. See [HeapDominatorTreemap.rootPathTo], which reads the objects out.
 *
 * One instance per tree rather than per question, because a treemap answers this for every rectangle the
 * pointer crosses and the arrays are the size of the heap dump. Which objects one walk has seen is
 * therefore stamped with the walk rather than cleared afterwards: clearing three arrays of a million ints
 * per rectangle would cost more than the walk does.
 */
internal class RootPathSearch(
  private val referrerIndex: ReferrerIndex,
  /** The objects a path can start at: the GC roots the tree was built from, and the garbage's own tops. */
  private val treeRootIndexes: IntSet
) {

  /** Which object each visited object was reached from, one step closer to the target. */
  private val nextTowardsTarget = IntArray(referrerIndex.objectCount)

  /** Which walk each object was last seen by, [NO_WALK] for one no walk has reached yet. */
  private val seenByWalk = IntArray(referrerIndex.objectCount)

  /** Breadth first, and an object is queued at most once per walk, so the dump's size is the bound. */
  private val queue = IntArray(referrerIndex.objectCount)

  private var walkCount = NO_WALK

  /** The shortest path from a root down to [targetIndex], source first, or null if there is none. */
  fun findPath(targetIndex: Int): IntArray? {
    // A GC root's own object, or a piece of garbage nothing points at: what reaches it is the root
    // itself, and walking up its referrers can't say so, because it has none to walk.
    if (targetIndex in treeRootIndexes) {
      return intArrayOf(targetIndex)
    }
    walkCount++
    // Seen from the start, so that no path found goes round through the object it leads to, which would
    // report the object as holding itself.
    seenByWalk[targetIndex] = walkCount
    queue[0] = targetIndex
    var head = 0
    var tail = 1
    while (head < tail) {
      val current = queue[head++]
      if (current in treeRootIndexes) {
        return pathFrom(current, targetIndex)
      }
      referrerIndex.forEachReferrer(current) { referrer ->
        if (seenByWalk[referrer] != walkCount) {
          seenByWalk[referrer] = walkCount
          nextTowardsTarget[referrer] = current
          queue[tail++] = referrer
        }
      }
    }
    return null
  }

  private fun pathFrom(
    sourceIndex: Int,
    targetIndex: Int
  ): IntArray {
    val path = mutableListOf(sourceIndex)
    var current = sourceIndex
    while (current != targetIndex) {
      current = nextTowardsTarget[current]
      path += current
    }
    return path.toIntArray()
  }

  companion object {
    /** No walk has this number: they start at 1, so a zeroed array has been seen by none of them. */
    private const val NO_WALK = 0
  }
}
