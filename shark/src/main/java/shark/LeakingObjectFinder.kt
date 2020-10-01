package shark

/**
 * Finds the objects that are leaking, for which Shark will compute
 * leak traces.
 *
 * This is a functional interface with which you can create a [LeakingObjectFinder] from a lambda.
 */
fun interface LeakingObjectFinder {

  /**
   * For a given heap graph, returns a set of object ids for the objects that are leaking.
   */
  fun findLeakingObjectIds(graph: HeapGraph): Set<Long>

  companion object {

    @Deprecated("Leverage Kotlin SAM lambda expression")
    inline operator fun invoke(crossinline block: (HeapGraph) -> Set<Long>): LeakingObjectFinder =
      object : LeakingObjectFinder {
        override fun findLeakingObjectIds(graph: HeapGraph): Set<Long> = block(graph)
      }
  }
}