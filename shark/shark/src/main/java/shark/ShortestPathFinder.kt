package shark

// TODO Mention that this can also compute the dominator tree?
//  Ideally this would be split out, e.g. ObjectDominators uses this without
//  any target just to navigate the whole graph. But we do want to be able traverse the
//  whole thing in one go, and both find leaking objects + compute dominators.
//  Maybe there's a new "heap traversal" class that can navigate the whole heap starting from
//  gc roots and following references, and we plug in 2 things on it: the one for leaks
//  and the one for dominators. And both can say when to stop (dominators: never).
fun interface ShortestPathFinder {
  fun findShortestPathsFromGcRoots(
    leakingObjectIds: Set<Long>
  ): PathFindingResults

  fun interface Factory {
    fun createFor(heapGraph: HeapGraph): ShortestPathFinder
  }
}
