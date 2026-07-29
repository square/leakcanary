package shark

/**
 * Computes the shallow size of heap objects, i.e. the size of an object itself, excluding the
 * objects it references.
 */
fun interface ObjectSizeCalculator {
  fun computeSize(objectId: Long): Long

  fun interface Factory {
    fun createFor(graph: HeapGraph): ObjectSizeCalculator
  }
}
