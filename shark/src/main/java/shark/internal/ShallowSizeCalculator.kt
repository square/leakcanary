package shark.internal

import shark.HeapGraph
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.ValueHolder

/**
 * Provides approximations for the shallow size of objects in memory.
 *
 * Determining the actual shallow size of an object in memory is hard, as it changes for each VM
 * implementation, depending on the various memory layout optimizations and bit alignment.
 *
 * More on this topic: https://dev.to/pyricau/the-real-size-of-android-objects-1i2e
 */
internal class ShallowSizeCalculator(private val graph: HeapGraph) {

  fun computeShallowSize(objectId: Long): Int {
    return when (val heapObject = graph.findObjectById(objectId)) {
      is HeapInstance -> {
        if (heapObject.instanceClassName == "java.lang.String") {
          // In PathFinder we ignore the value field of String instances when building the dominator
          // tree, so we add that size back here.
          val valueObjectId =
            heapObject["java.lang.String", "value"]?.value?.asNonNullObjectId
          heapObject.byteSize + if (valueObjectId != null) {
            computeShallowSize(valueObjectId)
          } else {
            0
          }
        } else {
          // Total byte size of fields for instances of this class, as registered in the class dump.
          // The actual memory layout likely differs.
          heapObject.byteSize
        }
      }
      // Number of elements * object id size
      is HeapObjectArray -> {
        if (heapObject.isSkippablePrimitiveWrapperArray) {
          // In PathFinder we ignore references sfrom primitive wrapper arrayss when building the
          // dominator tree, so we add that size back here.
          val elementIds = heapObject.readRecord().elementIds
          val shallowSize = elementIds.size * graph.identifierByteSize
          val firstNonNullElement = elementIds.firstOrNull { it != ValueHolder.NULL_REFERENCE }
          if (firstNonNullElement != null) {
            val sizeOfOneElement = computeShallowSize(firstNonNullElement)
            val countOfNonNullElements = elementIds.count { it != ValueHolder.NULL_REFERENCE }
            shallowSize + (sizeOfOneElement * countOfNonNullElements)
          } else {
            shallowSize
          }
        } else {
          heapObject.readByteSize()
        }
      }
      // Number of elements * primitive type size
      is HeapPrimitiveArray -> heapObject.readByteSize()
      // This is probably way off but is a cheap approximation.
      is HeapClass -> heapObject.recordSize
    }
  }
}