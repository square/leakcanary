package shark

// TODO Mention that this can also compute the dominator tree?
//  Maybe there's a new "heap traversal" class that can navigate the whole heap starting from
//  gc roots and following references.
fun interface ShortestPathFinder {
  fun findShortestPathsFromGcRoots(
    leakingObjectIds: Set<Long>
  ): PathFindingResults

  fun interface Factory {
    fun createFor(heapGraph: HeapGraph): ShortestPathFinder
  }
}
