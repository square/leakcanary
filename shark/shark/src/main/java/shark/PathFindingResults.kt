package shark

import shark.internal.ReferencePathNode

// TODO Class name
class PathFindingResults(
  val pathsToLeakingObjects: List<ReferencePathNode>,
  /**
   * Map of leaked object id → [Retained].
   * Null when [PrioritizingShortestPathFinder.Factory.objectSizeCalculatorFactory] is not provided.
   */
  val retainedSizes: Map<Long, Retained>?,
  /**
   * Map of parent leaked object id → list of sub-leaked object ids.
   * Sub-leaked objects are reachable from GC roots only through another leaked object, so they
   * are reported as labels on the parent's leak trace rather than as independent leaks.
   */
  val subLeakedObjectPaths: Map<Long, List<Long>>,
)
