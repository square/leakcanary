package shark.internal

import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray

/**
 * INTERNAL
 *
 * This class is public to be accessible from other LeakCanary modules but shouldn't be
 * called directly, the API may break at any point.
 */
object InternalSharkCollectionsHelper {

  fun arrayListValues(heapInstance: HeapInstance): Sequence<String> {
    val graph = heapInstance.graph
    val arrayListReader = OpenJdkInstanceRefReaders.ARRAY_LIST.create(graph)
      ?: ApacheHarmonyInstanceRefReaders.ARRAY_LIST.create(graph)
      ?: return emptySequence()

    if (!arrayListReader.matches(heapInstance)) {
      return emptySequence()
    }

    return arrayListReader.read(heapInstance).map { reference ->
      val arrayListValue = graph.findObjectById(reference.valueObjectId)
      val details = reference.lazyDetailsResolver.resolve()
      "[${details.name}] = ${className(arrayListValue)}"
    }
  }

  private fun className(graphObject: HeapObject): String {
    return when (graphObject) {
      is HeapClass -> {
        graphObject.name
      }
      is HeapInstance -> {
        graphObject.instanceClassName
      }
      is HeapObjectArray -> {
        graphObject.arrayClassName
      }
      is HeapPrimitiveArray -> {
        graphObject.arrayClassName
      }
    }
  }
}
