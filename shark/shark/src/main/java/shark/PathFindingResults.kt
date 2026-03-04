package shark

import shark.internal.ReferencePathNode

// TODO Class name
class PathFindingResults(
  val pathsToLeakingObjects: List<ReferencePathNode>,
  /**
   * R₀: the set of all object ids visited during Phase 1a (GC root BFS treating leaked objects
   * as leaves). Objects in this set are independently reachable from GC roots and should NOT
   * be attributed to any leaked node's retained size.
   */
  val visitedSet: HashSet<Long>,
  /**
   * Map of sub-leaked object id → list of parent leaked object ids that can reach it.
   * Only populated in Case B (some leaked objects are only reachable through other leaked objects).
   * Empty in Case A (all leaked objects found directly from GC roots).
   */
  val subLeakedObjectPaths: Map<Long, List<Long>>,
  /**
   * Reference reader for traversing heap object references during Phase 2 retained size BFS.
   */
  val objectReferenceReader: ReferenceReader<HeapObject>
)
