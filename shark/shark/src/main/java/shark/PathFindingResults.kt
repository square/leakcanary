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
   * retained by the leaking objects as a group. As a result the size reported for a single
   * leaking object is a lower bound of what fixing that leak alone would free.
   */
  val retainedSizes: LongObjectMap<Retained>?,
  /**
   * Leaking object id to the ids of the leaking objects that are only reachable through it.
   * These have no [pathsToLeakingObjects] entry of their own: they're reported as labels on the
   * leak trace of the leaking object they're keyed by.
   */
  val subLeakedObjectsByLeakedObject: LongObjectMap<LongArray>,
)
