@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import androidx.collection.MutableLongLongMap
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import shark.internal.hppc.LongDeque
import shark.internal.hppc.LongScatterSet
import shark.internal.packedWith
import shark.internal.unpackAsFirstInt
import shark.internal.unpackAsSecondInt

/**
 * Computes how much of the heap each group of growing objects reported by [ObjectGrowthDetector]
 * accounts for, using the LeakShare metric introduced by
 * [BLeak](https://plasma-umass.org/bleak-paper.pdf), the paper [ObjectGrowthDetector] is based on.
 *
 * ## Phase 1 — traversal from GC roots
 *
 * BFS from GC roots where the growing objects are treated as barriers: they're never visited and
 * their references are never explored. The visited set at the end of this phase is therefore
 * **R₀**: every object reachable from GC roots *without* going through a growing object. Objects
 * in R₀ stay in memory whether or not the growth is fixed, so they're not attributed to any
 * growing node.
 *
 * ## Phase 2 — traversal from the growing objects
 *
 * Each group of growing objects is then explored on its own, ignoring R₀, which visits exactly
 * the objects that are only reachable through growing objects. Attributing each of those objects
 * to a single group would be misleading here: growing objects that hold the same data (say two
 * collections that the leaked objects are added to) would each be credited a retained size of
 * roughly nothing, so neither would stand out. LeakShare instead splits an object evenly between
 * all the growing nodes that reach it: an object reached by 2 growing nodes contributes half of
 * its size to each. Sizes then still add up to the size of the subgraph the growing nodes retain
 * together, and a growing node that shares everything it holds with one other growing node is
 * reported at half of that subgraph rather than at zero.
 *
 * The trade off is that the reported size is no longer a lower bound of what fixing that one
 * growing node would free: freeing shared objects requires fixing every growing node that holds
 * them.
 *
 * Splitting requires knowing how many growing nodes reach an object before crediting any of them,
 * so phase 2 traverses each group twice: once to count, once to sum. Both traversals only cover
 * the subgraph that hangs off the growing objects, which is a small part of the heap.
 */
internal class LeakShareCalculator(
  private val graph: HeapGraph,
  private val gcRootProvider: GcRootProvider,
  private val objectReferenceReader: ReferenceReader<HeapObject>,
  private val objectSizeCalculator: ObjectSizeCalculator,
  private val estimatedVisitedObjects: Int,
) {

  /**
   * Returns the LeakShare of each group of object ids in [growingObjectIdGroups], in the same
   * order. Each group is the set of objects reported as growing by a single node of the shortest
   * path tree.
   */
  fun computeLeakShares(growingObjectIdGroups: List<LongArray>): List<Retained> {
    if (growingObjectIdGroups.isEmpty()) {
      return emptyList()
    }
    val growingObjectIds = LongScatterSet()
    growingObjectIdGroups.forEach { objectIds ->
      objectIds.forEach { objectId ->
        growingObjectIds += objectId
      }
    }
    val objectsReachableWithoutGrowth = LongScatterSet(estimatedVisitedObjects)
    findObjectsReachableWithoutGrowth(growingObjectIds, objectsReachableWithoutGrowth)

    // Object id to the count of groups that reach it, packed with the token of the last group
    // traversal that visited it, which is how each traversal knows what it already visited.
    val reachedObjects = MutableLongLongMap()

    growingObjectIdGroups.forEachIndexed { groupIndex, objectIds ->
      // Tokens start at 1 so that they never match the 0 of an object that hasn't been reached.
      val token = 1 + groupIndex
      visitObjectsRetainedByGroup(objectIds, objectsReachableWithoutGrowth) { objectId ->
        val reached = reachedObjects.getOrDefault(objectId, NOT_REACHED)
        if (reached.unpackAsSecondInt == token) {
          false
        } else {
          reachedObjects[objectId] = (reached.unpackAsFirstInt + 1) packedWith token
          true
        }
      }
    }

    return growingObjectIdGroups.mapIndexed { groupIndex, objectIds ->
      val token = 1 + growingObjectIdGroups.size + groupIndex
      var heapSize = 0.0
      var objectCount = 0.0
      visitObjectsRetainedByGroup(objectIds, objectsReachableWithoutGrowth) { objectId ->
        val reached = reachedObjects.getOrDefault(objectId, NOT_REACHED)
        val reachedFromGroupCount = reached.unpackAsFirstInt
        // A count of 0 would mean the counting traversal didn't reach that object, which can't
        // happen: both traversals of a group follow the same references.
        if (reachedFromGroupCount == 0 || reached.unpackAsSecondInt == token) {
          false
        } else {
          reachedObjects[objectId] = reachedFromGroupCount packedWith token
          heapSize += objectSizeCalculator.computeSize(objectId).toDouble() / reachedFromGroupCount
          objectCount += 1.0 / reachedFromGroupCount
          true
        }
      }
      Retained(
        heapSize = heapSize.roundToLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt().bytes,
        objectCount = objectCount.roundToInt()
      )
    }
  }

  /**
   * BFS from GC roots that stops at [growingObjectIds], adding to [visitedSet] R₀: every object
   * reachable from GC roots without going through a growing object.
   */
  private fun findObjectsReachableWithoutGrowth(
    growingObjectIds: LongScatterSet,
    visitedSet: LongScatterSet
  ) {
    val toVisitQueue = LongDeque()
    gcRootProvider.provideGcRoots(graph).forEach { gcRootReference ->
      val objectId = gcRootReference.gcRoot.id
      if (objectId == ValueHolder.NULL_REFERENCE || objectId in growingObjectIds) {
        return@forEach
      }
      if (visitedSet.add(objectId)) {
        toVisitQueue += objectId
      }
    }
    while (toVisitQueue.isNotEmpty()) {
      readReferences(toVisitQueue.poll()) { reference ->
        val objectId = reference.valueObjectId
        if (objectId !in growingObjectIds &&
          visitedSet.add(objectId) &&
          !reference.isLeafObject
        ) {
          toVisitQueue += objectId
        }
      }
    }
  }

  /**
   * BFS from [growingObjectIds] that skips the objects in [objectsReachableWithoutGrowth],
   * invoking [visit] for each object reached. [visit] returns whether this traversal reached that
   * object for the first time, in which case its references are explored as well.
   */
  private fun visitObjectsRetainedByGroup(
    growingObjectIds: LongArray,
    objectsReachableWithoutGrowth: LongScatterSet,
    visit: (Long) -> Boolean
  ) {
    val toVisitQueue = LongDeque()
    growingObjectIds.forEach { objectId ->
      if (visit(objectId)) {
        toVisitQueue += objectId
      }
    }
    while (toVisitQueue.isNotEmpty()) {
      readReferences(toVisitQueue.poll()) { reference ->
        val objectId = reference.valueObjectId
        if (objectId !in objectsReachableWithoutGrowth &&
          visit(objectId) &&
          !reference.isLeafObject
        ) {
          toVisitQueue += objectId
        }
      }
    }
  }

  private inline fun readReferences(
    objectId: Long,
    block: (Reference) -> Unit
  ) {
    objectReferenceReader.read(graph.findObjectById(objectId)).forEach(block)
  }

  companion object {
    /** Reached by 0 groups, visited by the traversal of token 0, i.e. by no traversal. */
    private val NOT_REACHED = 0 packedWith 0
  }
}
