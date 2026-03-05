package shark

import androidx.collection.LongLongMap
import shark.internal.ReferencePathNode

// TODO Class name
class PathFindingResults(
  val pathsToLeakingObjects: List<ReferencePathNode>,
  /**
   * Map of leaked object id → packed (retainedBytes, retainedCount).
   * Null when [PrioritizingShortestPathFinder.Factory.objectSizeCalculatorFactory] is not provided.
   */
  val retainedSizes: LongLongMap?,
  /**
   * Map of sub-leaked object id → parent leaked object id.
   * Sub-leaked objects are reachable from GC roots only through another leaked object, so they
   * are reported as labels on the parent's leak trace rather than as independent leaks.
   * Each sub-leaked object has exactly one parent because once found it is added to the visited
   * set and will not be attributed to any other seed.
   */
  val subLeakedObjectPaths: Map<Long, Long>,
)
