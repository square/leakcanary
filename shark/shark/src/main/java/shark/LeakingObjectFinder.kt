package shark

/**
 * Finds the objects that are leaking, for which Shark will compute
 * leak traces.
 */
fun interface LeakingObjectFinder {

  /**
   * For a given heap graph, returns a set of object ids for the objects that are leaking.
   */
  fun findLeakingObjectIds(graph: HeapGraph): Set<Long>
}
