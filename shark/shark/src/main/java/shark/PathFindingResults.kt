package shark

import androidx.collection.LongObjectMap
import shark.internal.ReferencePathNode

// TODO Class name
class PathFindingResults(
  /**
   * One path per leaking object that was reachable from GC roots without going through another
   * leaking object. Leaking objects reported in [subLeakedObjectsByLeakedObject] have no path
   * here.
   */
  val pathsToLeakingObjects: List<ReferencePathNode>,
  /**
   * Leaking object id to the heap size and object count it retains, or null when the
   * [ShortestPathFinder] didn't compute retained sizes.
   *
   * An object reachable from more than one leaking object is only counted once, towards one of
   * them, so these sizes never double count and always sum up to the size of the subgraph
   * retained by the leaking objects as a group. The trade off is that fixing one of those leaks on
   * its own frees less than the size reported for it, since the other leaking objects that also
   * reach that object would keep it alive.
   */
  val retainedSizes: LongObjectMap<Retained>?,
  /**
   * Leaking object id to the ids of the leaking objects that are only reachable through it.
   * These have no [pathsToLeakingObjects] entry of their own: they're reported as labels on the
   * leak trace of the leaking object they're keyed by.
   */
  val subLeakedObjectsByLeakedObject: LongObjectMap<LongArray>,
  /**
   * Leaking object id to the ids of the leaking objects that also retain it. Unlike
   * [subLeakedObjectsByLeakedObject], the key has a [pathsToLeakingObjects] entry of its own,
   * because it's also reachable without going through any leaking object. That's the path
   * reported, since it's the one that survives fixing those other leaks, and this map is what
   * lets its leak trace mention them.
   *
   * A leaking object reached from several leaking objects is only recorded against one of them,
   * and only when the [ShortestPathFinder] traverses the subgraphs retained by leaking objects at
   * all, which it skips when there are no retained sizes to compute and no leaking object left to
   * find.
   */
  val alsoRetainingLeakingObjectsByLeakedObject: LongObjectMap<LongArray>,
)
