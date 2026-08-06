package shark.explorer

import androidx.collection.IntSet

/**
 * The walk from one object out to the nearest of [treeRootIndexes], which is the way a GC root reaches it
 * that is easiest to make sense of: breadth first over the referrers, putting off the references that are
 * hard to read a leak from until there is nothing else left to walk.
 *
 * Which is `shark.PrioritizingShortestPathFinder`'s rule, read in the other direction — it walks down from
 * the GC roots and this walks up from the object — and the same rule for the same reason: a shortest path
 * is a truthful answer to "what holds this", not necessarily a useful one. The reference to put off is
 * whichever the reference reader marked [shark.Reference.isLowPriority], which is a running method's stack
 * frame, a reference known to leak in code an app doesn't control, and the arrays ART hangs off a class.
 * A path through a stack frame says an object was in use when the dump was taken, which is nothing to fix
 * and often one step from anywhere.
 *
 * **An object that shouldn't be in memory is put off for the same reason**, which is [leakingIndexes]: a
 * chain that runs through one says this object is held by a leak, and while there is another way to it
 * that isn't, that other way is what holds it once the leak is fixed. It is also what LeakCanary does,
 * where it reads as a different rule — its phase 1 stops at a leaking object rather than deprioritizing
 * one, so the way round is the only path it can find at all.
 *
 * So a path with none of those in it wins over a shorter one that has one, and once a walk is past one
 * there is no going back to the cheap kind: a chain is only as readable as its worst step. Among paths of
 * the same kind, the shortest. Which means a chain still runs through a leak when every way to the object
 * does — an object a leak dominates is held by that leak, and saying so is the whole answer.
 *
 * Object indexes rather than ids throughout, because that is what a [ReferrerIndex] walks in and what the
 * arrays here are indexed by. See [SemanticDominatorTreemap.rootPathTo], which reads the objects out.
 *
 * One instance per tree rather than per question, because a treemap answers this for every rectangle the
 * pointer crosses and the arrays are the size of the heap dump. Which objects one walk has seen is
 * therefore stamped with the walk rather than cleared afterwards: clearing four arrays of a million ints
 * per rectangle would cost more than the walk does.
 */
internal class RootPathSearch(
  private val referrerIndex: ReferrerIndex,
  /** The objects a path can start at: the GC roots the tree was built from, and the garbage's own tops. */
  private val treeRootIndexes: IntSet,
  /**
   * Which objects shouldn't be in memory, by object index. An array rather than a set of the few of them
   * there are, because it is read once per referrer of every object a walk reaches.
   */
  private val leakingIndexes: BooleanArray
) {

  /** Which object each visited object was reached from, one step closer to the target. */
  private val nextTowardsTarget = IntArray(referrerIndex.objectCount)

  /**
   * Which walk each object was last seen by, [NO_WALK] for one no walk has reached yet, and negated for
   * one queued in [lastQueue] — which is a bit rather than an array of its own, since the walk has to be
   * able to tell that an object it put off is now reachable without putting anything off.
   */
  private val seenByWalk = IntArray(referrerIndex.objectCount)

  /** Breadth first, and an object is queued at most once per walk, so the dump's size is the bound. */
  private val queue = IntArray(referrerIndex.objectCount)

  /** The objects only a reference worth putting off has reached, walked once [queue] runs out. */
  private val lastQueue = IntArray(referrerIndex.objectCount)

  private var walkCount = NO_WALK

  /** The path from a root down to [targetIndex], source first, or null if there is none. */
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
    var lastHead = 0
    var lastTail = 0
    // Everything the first pass reached without a reference worth putting off, then everything else. An
    // object queued in the second pass stays in it, since a path through it is already the second kind.
    var walkingLast = false
    while (head < tail || lastHead < lastTail) {
      val current = if (!walkingLast && head < tail) {
        queue[head++]
      } else {
        walkingLast = true
        lastQueue[lastHead++]
      }
      // Put off when it was queued and walked before this pass reached it, since something turned out to
      // hold it plainly. It is on this queue too, and there is nothing to do about that but skip it.
      if (walkingLast && seenByWalk[current] != -walkCount) {
        continue
      }
      if (current in treeRootIndexes) {
        return pathFrom(current, targetIndex)
      }
      referrerIndex.forEachReferrer(current) { referrer, isLowPriority ->
        // A step onto an object that shouldn't be in memory is a step this path is worse for, the same as a
        // low priority reference: every object above the target is a referrer of something on the way up.
        val putOff = walkingLast || isLowPriority || leakingIndexes[referrer]
        val seen = seenByWalk[referrer]
        // Not seen at all, or seen only through a reference worth putting off and this one isn't.
        if (seen != walkCount && (seen != -walkCount || !putOff)) {
          nextTowardsTarget[referrer] = current
          if (putOff) {
            seenByWalk[referrer] = -walkCount
            lastQueue[lastTail++] = referrer
          } else {
            seenByWalk[referrer] = walkCount
            queue[tail++] = referrer
          }
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
