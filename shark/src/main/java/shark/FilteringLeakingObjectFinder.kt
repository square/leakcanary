package shark

/**
 * Finds the objects that are leaking by scanning all objects in the heap dump
 * and delegating the decision to a list of [FilteringLeakingObjectFinder.LeakingObjectFilter]
 */
class FilteringLeakingObjectFinder(private val filters: List<LeakingObjectFilter>) :
    LeakingObjectFinder {

  /**
   * Filter to be passed to the [FilteringLeakingObjectFinder] constructor.
   */
  interface LeakingObjectFilter {
    /**
     * Returns whether the passed in [heapObject] is leaking. This should only return true
     * when we're 100% sure the passed in [heapObject] should not be in memory anymore.
     */
    fun isLeakingObject(heapObject: HeapObject): Boolean
  }

  override fun findLeakingObjectIds(graph: HeapGraph): Set<Long> {
    return graph.objects
        .filter { heapObject ->
          filters.any { filter ->
            filter.isLeakingObject(heapObject)
          }
        }
        .map { it.objectId }
        .toSet()
  }
}