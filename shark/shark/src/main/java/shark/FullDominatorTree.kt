@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "CANNOT_OVERRIDE_INVISIBLE_MEMBER")

package shark

import androidx.collection.LongLongMap
import androidx.collection.LongObjectMap
import androidx.collection.LongSet
import androidx.collection.MutableLongLongMap
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import androidx.collection.mutableLongObjectMapOf
import androidx.collection.mutableLongSetOf
import shark.DominatorTree.ObjectSizeCalculator
import shark.ObjectDominators.DominatorNode
import shark.internal.hppc.LongLongScatterMap
import shark.internal.hppc.LongLongScatterMap.ForEachCallback
import shark.internal.hppc.LongScatterSet
import shark.internal.packedWith
import shark.internal.unpackAsFirstInt
import shark.internal.unpackAsSecondInt

class FullDominatorTree(expectedElements: Int = 4) {

  /**
   * Map of objects to their dominator.
   *
   * If an object is dominated by more than one GC root then its dominator is set to
   * [ValueHolder.NULL_REFERENCE].
   */
  private val dominated = LongLongScatterMap(expectedElements)

  private val roots = mutableLongObjectMapOf<MutableLongSet>()

  operator fun contains(objectId: Long): Boolean = dominated.containsKey(objectId)

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
    check(objectId != ValueHolder.NULL_REFERENCE) {
      "Did not expect root to be marked as dominated"
    }
    val dominatedSlot = dominated.getSlot(objectId)

    val hasDominator = dominatedSlot != -1

    if (!hasDominator) {
      dominated[objectId] = parentObjectId
    } else if (parentObjectId == ValueHolder.NULL_REFERENCE) {
      val currentDominator = dominated.getSlotValue(dominatedSlot)
      // Currently dominated by multiple roots, but now we need to make this a root.
      if (currentDominator == objectId) {
        dominated[objectId] = ValueHolder.NULL_REFERENCE
        roots -= objectId
      }
    } else {
      // If the current dominator is root, then we should leave it at
      // that. same if new dominator is root? this is already taken care off.
      // Do we want to keep track of the types of GC roots though? Not sure it matters much.
      //
      //
      // if the current dominator is not root => we walk up common dominators. But if
      // we don't find anything in common, then we're going to decide that the two nearest dominators
      // are the real ones. We could mark that being setting their dominators as self, marking objects
      // dominated by multiple roots dominated by root.
      val currentDominator = dominated.getSlotValue(dominatedSlot)
      if (currentDominator == objectId) {
        // This dominator already has multiple dominator roots. We'll add one more by going up
        // the current dominator chain.
        var newRoot = parentObjectId
        var keepGoing = true
        var newRootIsMultiRoot = false
        while (keepGoing) {
          when (val nextNewRoot = dominated[newRoot]) {
            ValueHolder.NULL_REFERENCE -> {
              keepGoing = false
              newRootIsMultiRoot = false
            }

            newRoot -> {
              keepGoing = false
              newRootIsMultiRoot = true
            }

            else -> {
              newRoot = nextNewRoot
            }
          }
        }
        if (newRootIsMultiRoot) {
          val newMultiRoots = roots[newRoot]!!
          roots[objectId]!!.addAll(newMultiRoots)
        } else {
          roots[objectId]!!.add(newRoot)
        }
      } else if (currentDominator != ValueHolder.NULL_REFERENCE) {
        // We're looking for the Lowest Common Dominator between currentDominator and
        // parentObjectId. We know that currentDominator likely has a shorter dominator path than
        // parentObjectId since we're exploring the graph with a breadth first search. So we build
        // a temporary hash set for the dominator path of currentDominator (since it's smaller)
        // and then go through the dominator path of parentObjectId checking if any id exists
        // in that hash set.
        // Once we find either a common dominator or none, we update the map accordingly
        val currentDominators = LongScatterSet()
        var dominator = currentDominator
        var isRootOfExistingDominatorChainAMultiRoot = false
        var rootOfExistingDominatorChain = ValueHolder.NULL_REFERENCE
        while (dominator != ValueHolder.NULL_REFERENCE && !isRootOfExistingDominatorChainAMultiRoot) {
          rootOfExistingDominatorChain = dominator
          currentDominators.add(dominator)
          val newDominator = dominated[dominator]
          if (newDominator == dominator) {
            isRootOfExistingDominatorChainAMultiRoot = true
          } else {
            dominator = newDominator
          }
        }
        dominator = parentObjectId
        var isRootOfNewDominatorChainAMultiRoot = false
        var rootOfNewDominatorChain = ValueHolder.NULL_REFERENCE
        while (dominator != ValueHolder.NULL_REFERENCE && !isRootOfNewDominatorChainAMultiRoot) {
          if (dominator in currentDominators) {
            break
          }
          rootOfNewDominatorChain = dominator
          val newDominator = dominated[dominator]
          if (newDominator == dominator) {
            isRootOfNewDominatorChainAMultiRoot = true
          } else {
            dominator = newDominator
          }
        }
        // Nothing in common and new root is not a multi root
        if (dominator == ValueHolder.NULL_REFERENCE) {
          // Nothing in common between the two dominator chains. We need to created a multi root
          // for this node.
          dominated[objectId] = objectId
          val newMultiRoot = mutableLongSetOf()
          if (isRootOfExistingDominatorChainAMultiRoot) {
            newMultiRoot.addAll(roots[rootOfExistingDominatorChain]!!)
            newMultiRoot += rootOfNewDominatorChain
          } else {
            newMultiRoot += rootOfExistingDominatorChain
            newMultiRoot += rootOfNewDominatorChain
          }
          roots[objectId] = newMultiRoot
        } else if (isRootOfNewDominatorChainAMultiRoot) {
          // Nothing in common, new root is a multi root
          dominated[objectId] = objectId
          val newMultiRoot = mutableLongSetOf()
          if (isRootOfExistingDominatorChainAMultiRoot) {
            newMultiRoot.addAll(roots[rootOfNewDominatorChain]!!)
            newMultiRoot.addAll(roots[rootOfExistingDominatorChain]!!)
          } else {
            newMultiRoot.addAll(roots[rootOfNewDominatorChain]!!)
            newMultiRoot += rootOfExistingDominatorChain
          }
          roots[objectId] = newMultiRoot
        } else {
          dominated[objectId] = dominator
        }
      }
    }
    return hasDominator
  }

  private class MutableDominatorNode {
    var shallowSize = 0
    var retainedSize = 0
    var retainedCount = 0
    val dominated = mutableListOf<Long>()
  }

  fun buildFullDominatorTreeMultiRoot(objectSizeCalculator: ObjectSizeCalculator): Pair<LongObjectMap<DominatorNode>, Map<LongSet, DominatorNode>> {
    return buildFullDominatorTreeMultiRoot(objectSizeCalculator, true)
  }

  fun buildFullDominatorTree(objectSizeCalculator: ObjectSizeCalculator): LongObjectMap<DominatorNode> {
    return buildFullDominatorTreeMultiRoot(objectSizeCalculator, false).first
  }

  private fun buildFullDominatorTreeMultiRoot(
    objectSizeCalculator: ObjectSizeCalculator,
    multiRoot: Boolean
  ): Pair<LongObjectMap<DominatorNode>, Map<LongSet, DominatorNode>> {
    val dominators = mutableLongObjectMapOf<MutableDominatorNode>()
    val multiRoots = mutableMapOf<LongSet, MutableDominatorNode>()

    // if an instance is reachable by multiple roots, we want a multi root parent that holds
    // all things that are kept by the same set of N roots.

    // Reverse the dominated map to have dominators ids as keys and list of dominated as values
    dominated.forEach(ForEachCallback { key, value ->
      // create entry for dominated
      dominators.getOrPut(key) {
        MutableDominatorNode()
      }
      // multi root
      if (key == value) {
        if (multiRoot) {
          val keyMultiRoots = roots[key]!!
          multiRoots.getOrPut(keyMultiRoots) {
            MutableDominatorNode()
          }.dominated += key
        } else {
          dominators.getOrPut(ValueHolder.NULL_REFERENCE) {
            MutableDominatorNode()
          }.dominated += key
        }
      } else {
        // If dominator is null ref then we still have an entry for that, to collect all dominator
        // roots.
        dominators.getOrPut(value) {
          MutableDominatorNode()
        }.dominated += key
      }
    })

    val retainedSizes = computeRetainedSizes { objectId ->
      val shallowSize = objectSizeCalculator.computeSize(objectId)
      dominators[objectId]!!.shallowSize = shallowSize
      shallowSize
    }

    dominators.forEach { objectId, node ->
      if (objectId != ValueHolder.NULL_REFERENCE) {
        val retainedPacked = retainedSizes[objectId]
        val retainedSize = retainedPacked.unpackAsFirstInt
        val retainedCount = retainedPacked.unpackAsSecondInt
        node.retainedSize = retainedSize
        node.retainedCount = retainedCount
      }
    }

    multiRoots.forEach { (keyRoots, node) ->
      var retainedSize = 0
      var retainedCount = 0
      keyRoots.forEach {
        val keyRoot = dominators[it]!!
        retainedSize += keyRoot.retainedSize
        retainedCount += keyRoot.retainedCount
      }
      node.retainedSize = retainedSize
      node.retainedCount = retainedCount
    }

    val rootDominator = dominators[ValueHolder.NULL_REFERENCE]!!
    rootDominator.retainedSize = rootDominator.dominated.map { dominators[it]!!.retainedSize }.sum()
    rootDominator.retainedCount =
      rootDominator.dominated.map { dominators[it]!!.retainedCount }.sum()

    // Sort children with largest retained first
    dominators.forEach { _, node ->
      node.dominated.sortBy { -dominators.get(it)!!.retainedSize }
    }

    val finalDominators = MutableLongObjectMap<DominatorNode>(dominators.size)

    dominators.forEach { objectId, node ->
      finalDominators[objectId] = DominatorNode(
        node.shallowSize, node.retainedSize, node.retainedCount, node.dominated
      )
    }
    return finalDominators to multiRoots.mapValues { (_, node) ->
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
  private fun computeRetainedSizes(
    objectSizeCalculator: ObjectSizeCalculator
  ): LongLongMap {
    val nodeRetainedSizes = MutableLongLongMap(dominated.size)
    dominated.forEach(ForEachCallback { key, value ->
      val instanceSize = objectSizeCalculator.computeSize(key)
      nodeRetainedSizes[key] = instanceSize packedWith 1
    })

    dominated.forEach(object : ForEachCallback {
      override fun onEntry(
        key: Long,
        value: Long
      ) {

        // Adding our size to parents. If a root or multi root, nothing to report.
        // We can compute multi root sizes later by summing their root sizes.
        if (value != ValueHolder.NULL_REFERENCE && key != value) {
          val instanceSize = nodeRetainedSizes[key].unpackAsFirstInt
          var dominator = value
          var isMultiRoot = false
          while (dominator != ValueHolder.NULL_REFERENCE && !isMultiRoot) {
            // Update retained size for that node
            val dominatorRetained = nodeRetainedSizes[dominator]
            val currentRetainedSize = dominatorRetained.unpackAsFirstInt
            val currentRetainedCount = dominatorRetained.unpackAsSecondInt
            nodeRetainedSizes[dominator] =
              (currentRetainedSize + instanceSize) packedWith (currentRetainedCount + 1)
            val nextDominator = dominated[dominator]
            if (dominator == nextDominator) {
              isMultiRoot = true
            } else {
              dominator = nextDominator
            }
          }
        }
      }
    })
    dominated.release()

    return nodeRetainedSizes
  }
}

