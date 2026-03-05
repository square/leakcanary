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
   * This is kept as a [LongScatterSet] internally to avoid copying. It is not part of the
   * public API surface.
   */
  internal val visitedSet: LongScatterSet,
  /**
   * Map of sub-leaked object id → list of parent leaked object ids that can reach it.
   * Only populated after [PrioritizingShortestPathFinder.computeRetainedSizes] is called.
   * Empty when first returned from [ShortestPathFinder.findShortestPathsFromGcRoots].
   */
  val subLeakedObjectPaths: Map<Long, List<Long>>,
)
