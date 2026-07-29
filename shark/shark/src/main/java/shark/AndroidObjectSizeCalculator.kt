package shark

import shark.ContentReferences.SKIPPED
import shark.internal.ShallowSizeCalculator

/**
 * [contentReferences] must match the value the traversal this feeds was built with, see
 * [ContentReferences].
 */
class AndroidObjectSizeCalculator(
  graph: HeapGraph,
  contentReferences: ContentReferences = SKIPPED
) : ObjectSizeCalculator {

  private val nativeSizes = AndroidNativeSizeMapper(graph).mapNativeSizes()
  private val shallowSizeCalculator = ShallowSizeCalculator(graph, contentReferences)

  override fun computeSize(objectId: Long): Long {
    val nativeSize = nativeSizes[objectId] ?: 0
    val shallowSize = shallowSizeCalculator.computeShallowSize(objectId)
    return nativeSize + shallowSize
  }
}
