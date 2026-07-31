@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import androidx.collection.MutableLongLongMap
import androidx.collection.MutableLongSet
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import shark.internal.hppc.LongDeque
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
 * them. [LeakShare.exclusiveRetained] is that lower bound, reported alongside: the objects that no
 * other growing node reaches, which fixing this one growing node would free on its own.
 *
 * Splitting requires knowing how many growing nodes reach an object before crediting any of them.
 * Phase 2 therefore records which groups reach an object as a bit per group, then splits every
 * object reached in a single pass. Past [GROUPS_PER_MASK] groups there's no room for that, and it
 * falls back to traversing each group twice, once to count and once to sum. Either way phase 2 only
 * covers the subgraph that hangs off the growing objects, which is a small part of the heap.
 */
internal class LeakShareCalculator(
  private val graph: HeapGraph,
  private val gcRootProvider: GcRootProvider,
  private val objectReferenceReader: ReferenceReader<HeapObject>,
  private val objectSizeCalculator: ObjectSizeCalculator,
  /**
   * An empty set that phase 1 fills with R₀. Passed in rather than allocated here so that
   * [ObjectGrowthDetector] can hand over the set it tracked visited objects with, which it's done
   * with by the time leak shares are computed, instead of having a second set the size of the heap
   * allocated.
   */
  private val objectsReachableWithoutGrowth: MutableLongSet,
) {

  init {
    check(objectsReachableWithoutGrowth.isEmpty()) {
      "objectsReachableWithoutGrowth should be empty, " +
        "has ${objectsReachableWithoutGrowth.size} elements"
    }
  }

  /**
   * The share of the heap that one group of growing objects accounts for.
   */
  class LeakShare(
    /** The LeakShare of the group: objects it shares are split with the groups it shares with. */
    val retained: Retained,

    /**
     * The part of [retained] made of objects that no other group reaches, which is therefore a
     * lower bound of what fixing this one group would free.
     */
    val exclusiveRetained: Retained,
  )

  /**
   * Returns the [LeakShare] of each group of object ids in [growingObjectIdGroups], in the same
   * order. Each group is the set of objects reported as growing by a single node of the shortest
   * path tree.
   */
  fun computeLeakShares(growingObjectIdGroups: List<LongArray>): List<LeakShare> {
    if (growingObjectIdGroups.isEmpty()) {
      return emptyList()
    }
    val growingObjectIds = MutableLongSet()
    growingObjectIdGroups.forEach { objectIds ->
      objectIds.forEach { objectId ->
        growingObjectIds += objectId
      }
    }
    findObjectsReachableWithoutGrowth(growingObjectIds)

    return if (growingObjectIdGroups.size <= GROUPS_PER_MASK) {
      computeLeakSharesFromGroupMasks(growingObjectIdGroups)
    } else {
      computeLeakSharesFromGroupCounts(growingObjectIdGroups)
    }
  }

  /**
   * Traverses each group once, recording which groups reach an object as a bit per group, then
   * splits every object reached between the groups of its mask in a single pass. Only works for up
   * to [GROUPS_PER_MASK] groups, see [computeLeakSharesFromGroupCounts] for more.
   */
  private fun computeLeakSharesFromGroupMasks(
    growingObjectIdGroups: List<LongArray>
  ): List<LeakShare> {
    // Object id to the mask of the groups that reached it, which is also how each traversal knows
    // what it already visited: its own bit being set.
    val groupMasksByObjectId = MutableLongLongMap()

    growingObjectIdGroups.forEachIndexed { groupIndex, objectIds ->
      val groupMask = 1L shl groupIndex
      visitObjectsRetainedByGroup(objectIds) { objectId ->
        val previousMask = groupMasksByObjectId.getOrDefault(objectId, NO_GROUP)
        if (previousMask and groupMask != 0L) {
          false
        } else {
          groupMasksByObjectId[objectId] = previousMask or groupMask
          true
        }
      }
    }

    val heapSizes = DoubleArray(growingObjectIdGroups.size)
    val objectCounts = DoubleArray(growingObjectIdGroups.size)
    val exclusiveHeapSizes = LongArray(growingObjectIdGroups.size)
    val exclusiveObjectCounts = IntArray(growingObjectIdGroups.size)
    groupMasksByObjectId.forEach { objectId, groupMask ->
      val reachedFromGroupCount = groupMask.countOneBits()
      val objectHeapSize = objectSizeCalculator.computeSize(objectId)
      val heapSizeShare = objectHeapSize.toDouble() / reachedFromGroupCount
      val objectCountShare = 1.0 / reachedFromGroupCount
      var remainingGroupMask = groupMask
      while (remainingGroupMask != NO_GROUP) {
        val groupIndex = remainingGroupMask.countTrailingZeroBits()
        heapSizes[groupIndex] += heapSizeShare
        objectCounts[groupIndex] += objectCountShare
        if (reachedFromGroupCount == 1) {
          exclusiveHeapSizes[groupIndex] += objectHeapSize
          exclusiveObjectCounts[groupIndex]++
        }
        // Clears the lowest bit set, i.e. the group we just credited.
        remainingGroupMask = remainingGroupMask and (remainingGroupMask - 1)
      }
    }
    return growingObjectIdGroups.indices.map { groupIndex ->
      LeakShare(
        retained = retained(heapSizes[groupIndex], objectCounts[groupIndex]),
        exclusiveRetained = retained(
          exclusiveHeapSizes[groupIndex].toDouble(),
          exclusiveObjectCounts[groupIndex].toDouble()
        )
      )
    }
  }

  /**
   * Traverses each group twice, once to count how many groups reach each object and once to split
   * each object between them. [computeLeakSharesFromGroupMasks] does the same with a single
   * traversal per group, but there are more groups here than it has bits for.
   */
  private fun computeLeakSharesFromGroupCounts(
    growingObjectIdGroups: List<LongArray>
  ): List<LeakShare> {
    // Object id to the count of groups that reach it, packed with the token of the last group
    // traversal that visited it, which is how each traversal knows what it already visited.
    val reachedObjects = MutableLongLongMap()

    growingObjectIdGroups.forEachIndexed { groupIndex, objectIds ->
      // Tokens start at 1 so that they never match the 0 of an object that hasn't been reached.
      val token = 1 + groupIndex
      visitObjectsRetainedByGroup(objectIds) { objectId ->
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
      var exclusiveHeapSize = 0L
      var exclusiveObjectCount = 0
      visitObjectsRetainedByGroup(objectIds) { objectId ->
        val reached = reachedObjects.getOrDefault(objectId, NOT_REACHED)
        val reachedFromGroupCount = reached.unpackAsFirstInt
        // A count of 0 would mean the counting traversal didn't reach that object, which can't
        // happen: both traversals of a group follow the same references.
        if (reachedFromGroupCount == 0 || reached.unpackAsSecondInt == token) {
          false
        } else {
          reachedObjects[objectId] = reachedFromGroupCount packedWith token
          val objectHeapSize = objectSizeCalculator.computeSize(objectId)
          heapSize += objectHeapSize.toDouble() / reachedFromGroupCount
          objectCount += 1.0 / reachedFromGroupCount
          // This traversal is the only one that reaches that object, so it's this group's alone.
          if (reachedFromGroupCount == 1) {
            exclusiveHeapSize += objectHeapSize
            exclusiveObjectCount++
          }
          true
        }
      }
      LeakShare(
        retained = retained(heapSize, objectCount),
        exclusiveRetained = retained(
          exclusiveHeapSize.toDouble(),
          exclusiveObjectCount.toDouble()
        )
      )
    }
  }

  private fun retained(
    heapSize: Double,
    objectCount: Double
  ) = Retained(
    heapSize = heapSize.roundToLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt().bytes,
    objectCount = objectCount.roundToInt()
  )

  /**
   * BFS from GC roots that stops at [growingObjectIds], filling [objectsReachableWithoutGrowth]
   * with R₀: every object reachable from GC roots without going through a growing object.
   */
  private fun findObjectsReachableWithoutGrowth(growingObjectIds: MutableLongSet) {
    val toVisitQueue = LongDeque()
    gcRootProvider.provideGcRoots(graph).forEach { gcRootReference ->
      val objectId = gcRootReference.gcRoot.id
      if (objectId == ValueHolder.NULL_REFERENCE || objectId in growingObjectIds) {
        return@forEach
      }
      if (objectsReachableWithoutGrowth.add(objectId)) {
        toVisitQueue += objectId
      }
    }
    while (toVisitQueue.isNotEmpty()) {
      readReferences(toVisitQueue.poll()) { reference ->
        val objectId = reference.valueObjectId
        if (objectId !in growingObjectIds &&
          objectsReachableWithoutGrowth.add(objectId) &&
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

    /** An empty mask of groups, i.e. an object no group reached. */
    private const val NO_GROUP = 0L

    /** How many groups fit in the [Long] mask that says which groups reached an object. */
    private const val GROUPS_PER_MASK = 64
  }
}
