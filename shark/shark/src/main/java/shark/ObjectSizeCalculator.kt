package shark

fun interface ObjectSizeCalculator {
  fun computeSize(objectId: Long): Int

  fun interface Factory {
    fun createFor(graph: HeapGraph): ObjectSizeCalculator
  }
}
