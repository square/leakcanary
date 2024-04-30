package shark

import shark.DominatorTree.ObjectSizeCalculator
import shark.internal.ShallowSizeCalculator

class AndroidObjectSizeCalculator(graph: HeapGraph) : ObjectSizeCalculator {

  private val nativeSizes = AndroidNativeSizeMapper(graph).mapNativeSizes()
  private val shallowSizeCalculator = ShallowSizeCalculator(graph)

  override fun computeSize(objectId: Long): Int {
    val nativeSize = nativeSizes[objectId] ?: 0
    val shallowSize = shallowSizeCalculator.computeShallowSize(objectId)
    return nativeSize + shallowSize
  }
}
