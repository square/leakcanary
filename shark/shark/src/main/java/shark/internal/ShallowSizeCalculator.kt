package shark.internal

import shark.HeapGraph
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray

/**
 * Provides approximations for the shallow size of objects in memory.
 *
 * Determining the actual shallow size of an object in memory is hard, as it changes for each VM
 * implementation, depending on the various memory layout optimizations and bit alignment.
 *
 * More on this topic: https://dev.to/pyricau/the-real-size-of-android-objects-1i2e
 */
internal class ShallowSizeCalculator(private val graph: HeapGraph) {

  fun computeShallowSize(objectId: Long): Long {
    return when (val heapObject = graph.findObjectById(objectId)) {
      // Total byte size of fields for instances of this class, as registered in the class dump.
      // The actual memory layout likely differs.
      is HeapInstance -> heapObject.byteSize
      // Number of elements * object id size
      is HeapObjectArray -> heapObject.byteSize
      // Number of elements * primitive type size
      is HeapPrimitiveArray -> heapObject.byteSize
      // This is probably way off but is a cheap approximation.
      is HeapClass -> heapObject.recordSize
    }
  }
}
