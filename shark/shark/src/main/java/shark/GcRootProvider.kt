package shark

fun interface GcRootProvider {
  /**
   * Provides a sequence of GC Roots to traverse the graph from, ideally in a stable order.
   */
  fun provideGcRoots(graph: HeapGraph): Sequence<GcRootReference>
}
