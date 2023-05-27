package shark

fun interface LeakTracer {

  // TODO What should we do about exceptions here? union error or exception?
  fun traceObjects(objectIds: Set<Long>): LeaksAndUnreachableObjects

  fun interface Factory {
    fun createFor(heapGraph: HeapGraph): LeakTracer
  }
}
