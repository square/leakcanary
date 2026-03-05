@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import shark.internal.ReferencePathNode
import shark.internal.hppc.LongScatterSet

// TODO Class name
class PathFindingResults internal constructor(
  val pathsToLeakingObjects: List<ReferencePathNode>,
  /**
   * R₀: the set of all object ids visited during Phase 1 (GC root BFS treating leaked objects
   * as leaves). Objects in this set are independently reachable from GC roots and should NOT
   * be attributed to any leaked node's retained size.
   *
   * Kept as a [LongScatterSet] internally to avoid copying. Not part of the public API surface.
   */
  internal val visitedSet: LongScatterSet,
  /**
   * The original set of leaking object ids passed to [ShortestPathFinder.findShortestPathsFromGcRoots].
   * Kept internally so [PrioritizingShortestPathFinder.computeRetainedSizes] can determine which
   * leaked objects were not found in Phase 1 (reachable only through other leaked objects).
   */
  internal val leakingObjectIds: LongScatterSet,
)
