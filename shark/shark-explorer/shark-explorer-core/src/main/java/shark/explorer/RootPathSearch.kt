package shark.explorer

import androidx.collection.IntSet

/**
 * The walk from one object out to the nearest of [treeRootIndexes], which is the way a GC root reaches it
 * that is easiest to make sense of: breadth first over the referrers, putting off the ways that are hard to
 * read a leak from until there is nothing better left to walk.
 *
 * Which is `shark.PrioritizingShortestPathFinder`'s rule, read in the other direction — it walks down from
 * the GC roots and this walks up from the object — and the same rule for the same reason: a shortest path
 * is a truthful answer to "what holds this", not necessarily a useful one.
 *
 * **Three kinds of way, walked best first**, one queue each: [PLAIN], then [THROUGH_A_LEAK], then
 * [THROUGH_A_LOW_PRIORITY_REFERENCE]. So a way with nothing worth putting off on it wins over a shorter one
 * that has something, and once a walk is past one there is no going back to a better kind: a chain is only
 * as readable as its worst step. Among ways of the same kind, the shortest. Which means a chain still runs
 * through a leak when every way to the object does — an object a leak dominates is held by that leak, and
 * saying so is the whole answer.
 *
 * Object indexes rather than ids throughout, because that is what a [ReferrerIndex] walks in and what the
 * arrays here are indexed by. See [HeapDominatorTreemap.rootPathTo], which reads the objects out.
 *
 * One instance per tree rather than per question, because a treemap answers this for every rectangle the
 * pointer crosses and the arrays are the size of the heap dump. Which objects one walk has seen is
 * therefore stamped with the walk rather than cleared afterwards: clearing five arrays of a million ints
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
   * Which walk each object was last seen by and through which kind of way, as [seenThrough] packs the two:
   * the walk decides whether anything is known about the object at all, and the kind whether a way just
   * found is worth queueing it again for.
   */
  private val seenByWalk = IntArray(referrerIndex.objectCount)

  /**
   * One queue per kind of way, and an object is queued at most once per kind per walk, so the dump's size
   * is the bound on each of them.
   */
  private val queues = Array(KIND_COUNT) { IntArray(referrerIndex.objectCount) }

  /** Where each of them is being read and written, which is what a walk resets rather than the queues. */
  private val queueHeads = IntArray(KIND_COUNT)
  private val queueTails = IntArray(KIND_COUNT)

  private var walkCount = NO_WALK

  /** The path from a root down to [targetIndex], source first, or null if there is none. */
  fun findPath(targetIndex: Int): IntArray? {
    // A GC root's own object, or a piece of garbage nothing points at: what reaches it is the root
    // itself, and walking up its referrers can't say so, because it has none to walk.
    if (targetIndex in treeRootIndexes) {
      return intArrayOf(targetIndex)
    }
    walkCount++
    queueHeads.fill(0)
    queueTails.fill(0)
    // Seen from the start, so that no path found goes round through the object it leads to, which would
    // report the object as holding itself.
    seenByWalk[targetIndex] = seenThrough(PLAIN)
    queues[PLAIN][queueTails[PLAIN]++] = targetIndex
    var kind = PLAIN
    while (kind < KIND_COUNT) {
      if (queueHeads[kind] == queueTails[kind]) {
        // Nothing reachable this well is left, so the next kind down is now the best there is.
        kind++
        continue
      }
      val current = queues[kind][queueHeads[kind]++]
      // Queued here and then found a better way, which put it on a queue this one comes after. It is on
      // this queue too, and there is nothing to do about that but skip it.
      if (seenByWalk[current] != seenThrough(kind)) {
        continue
      }
      if (current in treeRootIndexes) {
        return pathFrom(current, targetIndex)
      }
      referrerIndex.forEachReferrer(current) { referrer, isLowPriority ->
        // A chain is as good as its worst step, so a way on through a referrer is the worse of the way
        // here and of the step itself.
        val referrerKind = maxOf(kind, kindOfStepTo(referrer, isLowPriority))
        val seen = seenByWalk[referrer]
        // Not seen by this walk at all, or seen only through a worse kind of way than this one.
        if (seen < seenThrough(PLAIN) || seen > seenThrough(referrerKind)) {
          nextTowardsTarget[referrer] = current
          seenByWalk[referrer] = seenThrough(referrerKind)
          queues[referrerKind][queueTails[referrerKind]++] = referrer
        }
      }
    }
    return null
  }

  /** Which kind a way is that steps onto [referrer], every object above the target being one. */
  private fun kindOfStepTo(
    referrer: Int,
    isLowPriority: Boolean
  ): Int = when {
    isLowPriority -> THROUGH_A_LOW_PRIORITY_REFERENCE
    leakingIndexes[referrer] -> THROUGH_A_LEAK
    else -> PLAIN
  }

  /**
   * The walk and the kind of way in one int, kinds ascending inside a walk, so that a smaller number is a
   * better way and every number of one walk is below every number of the next.
   *
   * One array rather than two because it is read once per referrer of every object a walk reaches, and a
   * walk is what the pointer moving over a rectangle asks for.
   */
  private fun seenThrough(kind: Int): Int = walkCount * KIND_COUNT + kind

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

    /** Nothing worth putting off on the way, which is the chain to draw whenever there is one. */
    private const val PLAIN = 0

    /**
     * Through an object that shouldn't be in memory, which is [leakingIndexes]: a chain that runs through
     * one says this object is held by a leak, and while there is another way to it that isn't, that other
     * way is what holds it once the leak is fixed. It is also what LeakCanary does, where it reads as a
     * different rule — its phase 1 stops at a leaking object rather than deprioritizing one, so the way
     * round is the only path it can find at all.
     */
    private const val THROUGH_A_LEAK = 1

    /**
     * Through whichever reference the reference reader marked [shark.Reference.isLowPriority], which is a
     * running method's stack frame, a reference known to leak in code an app doesn't control, and the
     * arrays ART hangs off a class. A path through a stack frame says an object was in use when the dump
     * was taken, which is nothing to fix and often one step from anywhere.
     *
     * **Below [THROUGH_A_LEAK], because a leak is an answer and a stack frame is not.** An object marked
     * leaking is a reader saying this is the thing to fix, so a chain running through it names that thing;
     * a chain that leaves it for a frame answers "what holds this" with "a method is running", which is
     * the one answer nobody can act on. Measured on `leak_asynctask_o.hprof`, where the two verdicts that
     * make the chain name the faulty reference used to send it onto the worker thread instead — the frame
     * being two steps from the activity where the executor is six — and a chain with no verdict on it
     * marks no reference. `notes/decisions.md` has the numbers.
     */
    private const val THROUGH_A_LOW_PRIORITY_REFERENCE = 2

    /** How many kinds of way there are, which is how many queues a walk works through. */
    private const val KIND_COUNT = 3
  }
}
