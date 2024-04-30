package shark

fun interface HeapGraphProvider {
  fun openHeapGraph(): CloseableHeapGraph

  /**
   * This allows external modules to add factory methods for implementations of this interface as
   * extension functions of this companion object.
   */
  companion object
}
