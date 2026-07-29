@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "CANNOT_OVERRIDE_INVISIBLE_MEMBER")

package shark

import androidx.collection.LongLongMap
import androidx.collection.LongSet
import androidx.collection.MutableLongLongMap
import shark.internal.hppc.LongLongScatterMap
import shark.internal.hppc.LongLongScatterMap.ForEachCallback
import shark.internal.hppc.LongScatterSet
import shark.internal.packedWith
import shark.internal.unpackAsFirstInt
import shark.internal.unpackAsSecondInt

/**
 * Builds an approximate dominator tree incrementally, as a graph is traversed breadth first.
 *
 * The approximation comes from [updateDominated] using a Lowest Common Ancestor walk over the
 * dominators known so far: when an edge to an already visited object is processed while the
 * parent's own dominator is still too specific, the child's dominator is left too specific as
 * well and never revisited. Retained sizes computed from such a tree over attribute.
 *
 * [HeapDominatorTree] is exact, but it needs the whole graph up front. This one only needs the
 * edges as they're traversed, which is why [ObjectGrowthDetector] still uses it.
 */
internal class ApproximateDominatorTree(expectedElements: Int = 4) {

  /**
   * Map of objects to their dominator.
   *
   * If an object is dominated by more than one GC root then its dominator is set to
   * [ValueHolder.NULL_REFERENCE].
   */
  private val dominated = LongLongScatterMap(expectedElements)

  operator fun contains(objectId: Long): Boolean = dominated.containsKey(objectId)

  /**
   * Returns the dominator object id or [ValueHolder.NULL_REFERENCE] if [dominatedObjectId] is the
   * root dominator.
   */
  operator fun get(dominatedObjectId: Long) = dominated[dominatedObjectId]

  /**
   * Records that [objectId] is a root.
   */
  fun updateDominatedAsRoot(objectId: Long): Boolean {
    return updateDominated(objectId, ValueHolder.NULL_REFERENCE)
  }

  /**
   * Records that [objectId] can be reached through [parentObjectId], updating the dominator for
   * [objectId] to be either [parentObjectId] if [objectId] has no known dominator and otherwise to
   * the Lowest Common Dominator between [parentObjectId] and the previously determined dominator
   * for [objectId].
   *
   * [parentObjectId] should already have been added via [updateDominatedAsRoot]. Failing to do
   * that will throw [IllegalStateException] on future calls.
   *
   * This implementation is optimized with the assumption that the graph is visited as a breadth
   * first search, so when objectId already has a known dominator then its dominator path is
   * shorter than the dominator path of [parentObjectId].
   *
   * @return true if [objectId] already had a known dominator, false otherwise.
   */
  fun updateDominated(
    objectId: Long,
    parentObjectId: Long
  ): Boolean {
    val dominatedSlot = dominated.getSlot(objectId)

    val hasDominator = dominatedSlot != -1

    if (!hasDominator || parentObjectId == ValueHolder.NULL_REFERENCE) {
      dominated[objectId] = parentObjectId
    } else {
      val currentDominator = dominated.getSlotValue(dominatedSlot)
      if (currentDominator != ValueHolder.NULL_REFERENCE) {
        // We're looking for the Lowest Common Dominator between currentDominator and
        // parentObjectId. We know that currentDominator likely has a shorter dominator path than
        // parentObjectId since we're exploring the graph with a breadth first search. So we build
        // a temporary hash set for the dominator path of currentDominator (since it's smaller)
        // and then go through the dominator path of parentObjectId checking if any id exists
        // in that hash set.
        // Once we find either a common dominator or none, we update the map accordingly
        val currentDominators = LongScatterSet()
        var dominator = currentDominator
        while (dominator != ValueHolder.NULL_REFERENCE) {
          currentDominators.add(dominator)
          val nextDominatorSlot = dominated.getSlot(dominator)
          if (nextDominatorSlot == -1) {
            throw IllegalStateException(
              "Did not find dominator for $dominator when going through the dominator chain " +
                "for $currentDominator: $currentDominators"
            )
          } else {
            dominator = dominated.getSlotValue(nextDominatorSlot)
          }
        }
        dominator = parentObjectId
        while (dominator != ValueHolder.NULL_REFERENCE) {
          if (dominator in currentDominators) {
            break
          }
          val nextDominatorSlot = dominated.getSlot(dominator)
          if (nextDominatorSlot == -1) {
            throw IllegalStateException(
              "Did not find dominator for $dominator when going through the dominator chain for $parentObjectId"
            )
          } else {
            dominator = dominated.getSlotValue(nextDominatorSlot)
          }
        }
        dominated[objectId] = dominator
      }
    }
    return hasDominator
  }

  /**
   * Computes the size retained by [retainedObjectIds] using the dominator tree built using
   * [updateDominated]. The shallow size of each object is provided by [objectSizeCalculator].
   * @return a map of object id to retained size.
   */
  fun computeRetainedSizes(
    retainedObjectIds: LongSet,
    objectSizeCalculator: ObjectSizeCalculator
  ): LongLongMap {
    val nodeRetainedSizes = MutableLongLongMap(retainedObjectIds.size)
    retainedObjectIds.forEach { objectId ->
      nodeRetainedSizes[objectId] = 0 packedWith 0
    }

    dominated.forEach(object : ForEachCallback {
      override fun onEntry(
        key: Long,
        value: Long
      ) {
        // lazy computing of instance size
        var instanceSize = -1

        // If the entry is a node, add its size to nodeRetainedSizes

        val missing = -1 packedWith -1
        val packedRetained = nodeRetainedSizes.getOrDefault(key, missing)
        if (packedRetained != missing) {
          val currentRetainedSize = packedRetained.unpackAsFirstInt
          val currentRetainedCount = packedRetained.unpackAsSecondInt
          instanceSize = objectSizeCalculator.computeSize(key)
          nodeRetainedSizes[key] =
            (currentRetainedSize + instanceSize) packedWith currentRetainedCount + 1
        }

        if (value != ValueHolder.NULL_REFERENCE) {
          var dominator = value
          val dominatedByNextNode = mutableListOf(key)
          while (dominator != ValueHolder.NULL_REFERENCE) {
            // If dominator is a node
            if (nodeRetainedSizes.containsKey(dominator)) {
              // Update dominator for all objects in the dominator path so far to directly point
              // to it. We're compressing the dominator path to make this iteration faster and
              // faster as we go through each entry.
              dominatedByNextNode.forEach { objectId ->
                dominated[objectId] = dominator
              }
              if (instanceSize == -1) {
                instanceSize = objectSizeCalculator.computeSize(key)
              }
              // Update retained size for that node
              val dominatorRetained = nodeRetainedSizes[dominator]
              val currentRetainedSize = dominatorRetained.unpackAsFirstInt
              val currentRetainedCount = dominatorRetained.unpackAsSecondInt
              nodeRetainedSizes[dominator] =
                (currentRetainedSize + instanceSize) packedWith (currentRetainedCount + 1)
              dominatedByNextNode.clear()
            } else {
              dominatedByNextNode += dominator
            }
            dominator = dominated[dominator]
          }
          // Update all dominator for all objects found in the dominator path after the last node
          dominatedByNextNode.forEach { objectId ->
            dominated[objectId] = ValueHolder.NULL_REFERENCE
          }
        }
      }
    })
    dominated.release()

    return nodeRetainedSizes
  }
}
