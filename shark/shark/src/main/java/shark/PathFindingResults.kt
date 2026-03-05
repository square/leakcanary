package shark

import androidx.collection.LongLongMap
import shark.internal.ReferencePathNode

// TODO Class name
class PathFindingResults internal constructor(
  val pathsToLeakingObjects: List<ReferencePathNode>,
  /**
   * Map of leaked object id → packed (retainedBytes, retainedCount).
   * Only populated when [PrioritizingShortestPathFinder.Factory.objectSizeCalculatorFactory]
   * is provided.
   */
  val retainedSizes: LongLongMap,
  /**
   * Map of sub-leaked object id → list of parent leaked object ids that can reach it.
   * Sub-leaked objects are reachable from GC roots only through another leaked object, so they
   * are reported as labels on the parent's leak trace rather than as independent leaks.
   */
  val subLeakedObjectPaths: Map<Long, List<Long>>,
)
