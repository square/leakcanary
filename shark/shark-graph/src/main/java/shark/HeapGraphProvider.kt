package shark

fun interface HeapGraphProvider {
  fun openHeapGraph(): CloseableHeapGraph
}
