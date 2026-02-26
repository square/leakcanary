@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "CANNOT_OVERRIDE_INVISIBLE_MEMBER")

package shark

import androidx.collection.LongLongMap
import androidx.collection.LongSet
import androidx.collection.MutableLongLongMap
import androidx.collection.MutableLongSet
import shark.ObjectDominators.DominatorNode
import shark.internal.hppc.LongLongScatterMap
import shark.internal.hppc.LongLongScatterMap.ForEachCallback
import shark.internal.hppc.LongScatterSet
import shark.internal.packedWith
import shark.internal.unpackAsFirstInt
import shark.internal.unpackAsSecondInt

/**
 * Builds a dominator tree incrementally during a BFS traversal of the heap graph, using a
 * Lowest Common Ancestor (LCA) approach to update immediate dominators as new parents are
 * discovered.
 *
 * **Known limitation**: this is an approximation, not an exact dominator algorithm (compare
 * with Lengauer-Tarjan or Cooper et al.'s iterative algorithm). It can produce incorrect
 * immediate dominators when a cross-edge (to an already-visited node) is processed using a
 * stale parent dominator, and that parent's dominator is later raised by another cross-edge
 * after the first cross-edge's children have already been settled. The child's dominator is then
 * left too specific (too far from root), so its retained size is under-attributed — some
 * objects that it truly retains exclusively are instead attributed to a higher ancestor. See
 * [updateDominated] for a concrete example and [DominatorTreeTest] for a test documenting this
 * behavior.
 *
 * **Fix**: pass `collectCrossEdges = true` to the constructor, then call [runConvergenceLoop]
 * after the BFS traversal completes and before calling [computeRetainedSizes] or
 * [buildFullDominatorTree]. The convergence loop re-processes stored cross-edges with updated
 * dominator values until the tree stabilizes, typically within 2–3 extra passes.
 */
class DominatorTree(
  expectedElements: Int = 4,
  collectCrossEdges: Boolean = false
) {

  fun interface ObjectSizeCalculator {
    fun computeSize(objectId: Long): Int
  }

  /**
   * Map of objects to their dominator.
   *
   * If an object is dominated by more than one GC root then its dominator is set to
   * [ValueHolder.NULL_REFERENCE].
   */
  private val dominated = LongLongScatterMap(expectedElements)

  /**
   * Stores cross-edges (each as a 2-element LongArray of [objectId, parentObjectId]) discovered
   * during BFS, for use by [runConvergenceLoop]. Only populated when `collectCrossEdges = true`.
   */
  private val crossEdges: MutableList<LongArray>? = if (collectCrossEdges) mutableListOf() else null

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
   * **Known limitation**: the BFS assumption breaks down when a cross-edge (to an already-visited
   * node) is processed with a stale parent dominator. Consider:
   * ```
   *   root → A → B → C   (tree edges; B discovered first via A)
   *   root → A → C       (C also directly reachable from A)
   *   root → D → E → B   (E→B is a cross-edge at same BFS level as B)
   * ```
   * BFS dequeues B before E (both at level 2). When B is dequeued, B→C is processed as a
   * cross-edge with dom(B) = A (stale — E→B hasn't been processed yet). LCA(dom(C)=A, B) with
   * dom(B)=A returns A, so dom(C) stays A. Later, when E is dequeued, E→B is processed and
   * correctly raises dom(B) to root (root→D→E→B bypasses A). But dom(C) is never revisited:
   * it remains A, even though the path root→D→E→B→C bypasses A, making root the true idom(C).
   *
   * The consequence is that dom(C) is left too specific (too far from root), so C's retained
   * size is incorrectly attributed to A instead of root. A's retained size is over-reported.
   *
   * To fix this, construct with `collectCrossEdges = true` and call [runConvergenceLoop] after
   * the full BFS traversal and before [computeRetainedSizes] or [buildFullDominatorTree].
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
        // Only record this cross-edge if both dom(objectId) and dom(parentObjectId) are
        // non-null. If either is already NULL_REFERENCE the convergence loop would always
        // produce a no-op: a NULL_REFERENCE currentDominator can't be raised further, and a
        // NULL_REFERENCE parent dominator means the LCA walk terminates in one step at the
        // same value already set by this call.
        if (crossEdges != null) {
          val parentSlot = dominated.getSlot(parentObjectId)
          if (parentSlot != -1 && dominated.getSlotValue(parentSlot) != ValueHolder.NULL_REFERENCE) {
            crossEdges.add(longArrayOf(objectId, parentObjectId))
          }
        }
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
              "Did not find dominator for $dominator when going through the dominator chain for $currentDominator: $currentDominators"
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
   * Re-processes stored cross-edges until the dominator tree stabilizes or [maxIterations] is
   * reached. Must be called after the BFS traversal is complete and before calling
   * [computeRetainedSizes] or [buildFullDominatorTree].
   *
   * This fixes cases where a cross-edge `P→C` was processed using a stale `dom(P)` value.
   * When `dom(P)` is later raised by another cross-edge, `dom(C)` is not automatically updated.
   * The convergence loop re-runs the LCA computation for each stored cross-edge until no
   * further changes occur, propagating all such corrections.
   *
   * Requires `collectCrossEdges = true` to have been passed to the constructor; throws
   * [IllegalStateException] otherwise.
   *
   * @param maxIterations maximum number of passes over the cross-edge set. Pass [Int.MAX_VALUE]
   *   to run until fully stable. Heap graphs typically converge in 2–3 iterations.
   * @return the number of iterations performed.
   */
  fun runConvergenceLoop(maxIterations: Int = Int.MAX_VALUE): Int {
    val edges = crossEdges
      ?: error("Cross-edge collection not enabled. Construct DominatorTree with collectCrossEdges = true.")
    var iterations = 0
    var changed = true
    while (changed && iterations < maxIterations) {
      changed = false
      iterations++
      for (edge in edges) {
        val objectId = edge[0]
        val parentObjectId = edge[1]
        val dominatedSlot = dominated.getSlot(objectId)
        if (dominatedSlot == -1) continue
        val currentDominator = dominated.getSlotValue(dominatedSlot)
        // If already attributed to the virtual root, it cannot be raised further.
        if (currentDominator == ValueHolder.NULL_REFERENCE) continue
        // LCA computation: same approach as updateDominated.
        val currentDominators = LongScatterSet()
        var dominator = currentDominator
        while (dominator != ValueHolder.NULL_REFERENCE) {
          currentDominators.add(dominator)
          val nextSlot = dominated.getSlot(dominator)
          if (nextSlot == -1) {
            throw IllegalStateException(
              "Did not find dominator for $dominator when going through the dominator chain for $currentDominator: $currentDominators"
            )
          } else {
            dominator = dominated.getSlotValue(nextSlot)
          }
        }
        dominator = parentObjectId
        while (dominator != ValueHolder.NULL_REFERENCE) {
          if (dominator in currentDominators) break
          val nextSlot = dominated.getSlot(dominator)
          if (nextSlot == -1) {
            throw IllegalStateException(
              "Did not find dominator for $dominator when going through the dominator chain for $parentObjectId"
            )
          } else {
            dominator = dominated.getSlotValue(nextSlot)
          }
        }
        if (dominator != currentDominator) {
          dominated[objectId] = dominator
          changed = true
        }
      }
      // Prune edges whose object has reached NULL_REFERENCE so subsequent passes are cheaper.
      if (changed) {
        edges.removeAll { edge ->
          val slot = dominated.getSlot(edge[0])
          slot == -1 || dominated.getSlotValue(slot) == ValueHolder.NULL_REFERENCE
        }
      }
    }
    return iterations
  }

  private class MutableDominatorNode {
    var shallowSize = 0
    var retainedSize = 0
    var retainedCount = 0
    val dominated = mutableListOf<Long>()
  }

  fun buildFullDominatorTree(objectSizeCalculator: ObjectSizeCalculator): Map<Long, DominatorNode> {
    val dominators = mutableMapOf<Long, MutableDominatorNode>()
    // Reverse the dominated map to have dominators ids as keys and list of dominated as values
    dominated.forEach(ForEachCallback { key, value ->
      // create entry for dominated
      dominators.getOrPut(key) {
        MutableDominatorNode()
      }
      // If dominator is null ref then we still have an entry for that, to collect all dominator
      // roots.
      dominators.getOrPut(value) {
        MutableDominatorNode()
      }.dominated += key
    })

    val allReachableObjectIds = MutableLongSet(dominators.size)
    dominators.forEach { (key, _) ->
      if (key != ValueHolder.NULL_REFERENCE) {
        allReachableObjectIds += key
      }
    }

    val retainedSizes = computeRetainedSizes(allReachableObjectIds) { objectId ->
      val shallowSize = objectSizeCalculator.computeSize(objectId)
      dominators.getValue(objectId).shallowSize = shallowSize
      shallowSize
    }

    dominators.forEach { (objectId, node) ->
      if (objectId != ValueHolder.NULL_REFERENCE) {
        val retainedPacked = retainedSizes[objectId]
        val retainedSize = retainedPacked.unpackAsFirstInt
        val retainedCount = retainedPacked.unpackAsSecondInt
        node.retainedSize = retainedSize
        node.retainedCount = retainedCount
      }
    }

    val rootDominator = dominators.getValue(ValueHolder.NULL_REFERENCE)
    rootDominator.retainedSize = rootDominator.dominated.map { dominators[it]!!.retainedSize }.sum()
    rootDominator.retainedCount =
      rootDominator.dominated.map { dominators[it]!!.retainedCount }.sum()

    // Sort children with largest retained first
    dominators.values.forEach { node ->
      node.dominated.sortBy { -dominators.getValue(it).retainedSize }
    }

    return dominators.mapValues { (_, node) ->
      DominatorNode(
        node.shallowSize, node.retainedSize, node.retainedCount, node.dominated
      )
    }
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

