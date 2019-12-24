package shark

import shark.ObjectInspector.Companion.invoke

/**
 * Finds the objects that are leaking, for which Shark will compute
 * leak traces.
 *
 * You can create a [LeakingObjectFinder] from a lambda by calling [invoke].
 */
interface LeakingObjectFinder {

  /**
   * For a given heap graph, returns a set of object ids for the objects that are leaking.
   */
  fun findLeakingObjectIds(graph: HeapGraph): Set<Long>

  companion object {
    /**
     * Utility function to create a [LeakingObjectFinder] from the passed in [block] lambda
     * instead of using the anonymous `object : LeakingObjectFinder` syntax.
     *
     * Usage:
     *
     * ```kotlin
     * val listener = LeakingObjectFinder {
     *
     * }
     * ```
     */
    inline operator fun invoke(crossinline block: (HeapGraph) -> Set<Long>): LeakingObjectFinder =
      object : LeakingObjectFinder {
        override fun findLeakingObjectIds(graph: HeapGraph): Set<Long> = block(graph)

      }
  }

}