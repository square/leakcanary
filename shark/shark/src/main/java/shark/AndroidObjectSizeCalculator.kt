package shark

import shark.internal.ShallowSizeCalculator

class AndroidObjectSizeCalculator(graph: HeapGraph) : ObjectSizeCalculator {

  private val nativeSizes = AndroidNativeSizeMapper(graph).mapNativeSizes()
  private val shallowSizeCalculator = ShallowSizeCalculator(graph)

  override fun computeSize(objectId: Long): Long {
    val nativeSize = nativeSizes[objectId] ?: 0
    val shallowSize = shallowSizeCalculator.computeShallowSize(objectId)
    return nativeSize + shallowSize
  }
}
