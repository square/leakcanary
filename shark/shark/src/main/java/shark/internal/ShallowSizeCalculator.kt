package shark.internal

import shark.ContentReferences
import shark.ContentReferences.SKIPPED
import shark.HeapGraph
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.ObjectArrayReferenceReader.Companion.isSkippablePrimitiveWrapperArray
import shark.ValueHolder

/**
 * Provides approximations for the shallow size of objects in memory.
 *
 * Determining the actual shallow size of an object in memory is hard, as it changes for each VM
 * implementation, depending on the various memory layout optimizations and bit alignment.
 *
 * More on this topic: https://dev.to/pyricau/the-real-size-of-android-objects-1i2e
 *
 * [contentReferences] must match the value the traversal this feeds was built with, see
 * [ContentReferences].
 */
internal class ShallowSizeCalculator(
  private val graph: HeapGraph,
  private val contentReferences: ContentReferences = SKIPPED
) {

  fun computeShallowSize(objectId: Long): Long {
    return when (val heapObject = graph.findObjectById(objectId)) {
      is HeapInstance -> {
        if (contentReferences == SKIPPED && heapObject.instanceClassName == "java.lang.String") {
          // The traversal ignored the value field of String instances, so we add that size back
          // here. Strings that share their value array each get credited for the whole array.
          val valueObjectId =
            heapObject["java.lang.String", "value"]?.value?.asNonNullObjectId
          heapObject.byteSize + if (valueObjectId != null) {
            computeShallowSize(valueObjectId)
          } else {
            0L
          }
        } else {
          // Total byte size of fields for instances of this class, as registered in the class dump.
          // The actual memory layout likely differs.
          heapObject.byteSize
        }
      }
      // Number of elements * object id size
      is HeapObjectArray -> {
        if (contentReferences == SKIPPED && heapObject.isSkippablePrimitiveWrapperArray) {
          // The traversal ignored references from primitive wrapper arrays, so we add that size
          // back here. Boxed primitives are cached and shared, so an array gets credited for every
          // slot pointing at a shared instance.
          val elementIds = heapObject.readRecord().elementIds
          val shallowSize = elementIds.size.toLong() * graph.identifierByteSize
          val firstNonNullElement = elementIds.firstOrNull { it != ValueHolder.NULL_REFERENCE }
          if (firstNonNullElement != null) {
            val sizeOfOneElement = computeShallowSize(firstNonNullElement)
            val countOfNonNullElements = elementIds.count { it != ValueHolder.NULL_REFERENCE }
            shallowSize + (sizeOfOneElement * countOfNonNullElements)
          } else {
            shallowSize
          }
        } else {
          heapObject.byteSize
        }
      }
      // Number of elements * primitive type size
      is HeapPrimitiveArray -> heapObject.byteSize
      // This is probably way off but is a cheap approximation.
      is HeapClass -> heapObject.recordSize
    }
  }
}
