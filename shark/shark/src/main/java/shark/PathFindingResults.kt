package shark

import shark.internal.ReferencePathNode

// TODO Class name
class PathFindingResults(
  val pathsToLeakingObjects: List<ReferencePathNode>,
  val dominatorTree: DominatorTree?
)
