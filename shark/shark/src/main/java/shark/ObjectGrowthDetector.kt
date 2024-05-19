@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import androidx.collection.IntIntPair
import androidx.collection.MutableLongLongMap
import androidx.collection.MutableLongSet
import java.util.ArrayDeque
import java.util.Deque
import shark.ByteSize.Companion.bytes
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.ReferenceLocationType.ARRAY_ENTRY
import shark.ShortestPathObjectNode.Retained
import shark.internal.hppc.LongScatterSet

/**
 * Looks for objects that have grown in outgoing references in a new heap dump compared to a
 * previous heap dump by diffing heap traversals.
 */
class ObjectGrowthDetector(
  private val gcRootProvider: GcRootProvider,
  private val referenceReaderFactory: ReferenceReader.Factory<HeapObject>,
) {

  fun findGrowingObjects(
    heapGraph: CloseableHeapGraph,
    previousTraversal: HeapTraversalInput = InitialState(),
  ): HeapTraversalOutput {
    check(previousTraversal !is HeapGrowthTraversal || previousTraversal.isGrowing) {
      "Previous HeapGrowth traversal was not growing, there's no reason to run this again. " +
        "previousTraversal:$previousTraversal"
    }

    // Estimate of how many objects we'll visit. This is a conservative estimate, we should always
    // visit more than that but this limits the number of early array growths.
    val estimatedVisitedObjects = (heapGraph.instanceCount / 2).coerceAtLeast(4)
    val state = TraversalState(estimatedVisitedObjects = estimatedVisitedObjects)
    return heapGraph.use {
      state.traverseHeapDiffingShortestPaths(
        heapGraph,
        previousTraversal
      )
    }.also { output ->
      if (output is HeapGrowthTraversal) {
        val scenarioCount = output.traversalCount * output.scenarioLoopsPerGraph
        SharkLog.d {
          "After $scenarioCount scenario iterations and ${output.traversalCount} heap dumps, " +
            "${output.growingObjects.size} growing nodes:\n" + output.growingObjects
        }
      }
    }
  }

  private class TraversalState(
    estimatedVisitedObjects: Int
  ) {
    var visitingLast = false

    /** Set of objects to visit */
    val toVisitQueue: Deque<Node> = ArrayDeque()

    /**
     * Paths to visit when [toVisitQueue] is empty.
     */
    val toVisitLastQueue: Deque<Node> = ArrayDeque()

    val visitedSet = LongScatterSet(estimatedVisitedObjects)

    // Not using estimatedVisitedObjects because there could be a lot less nodes than objects.
    // This is a list because order matters.
    val dequeuedNodes = mutableListOf<Node>()
    val dominatorTree = DominatorTree(estimatedVisitedObjects)

    val tree = ShortestPathObjectNode("root", null).apply {
      selfObjectCount = 1
    }
    val queuesNotEmpty: Boolean
      get() = toVisitQueue.isNotEmpty() || toVisitLastQueue.isNotEmpty()
  }

  @Suppress("ComplexMethod")
  private fun TraversalState.traverseHeapDiffingShortestPaths(
    graph: CloseableHeapGraph,
    previousTraversal: HeapTraversalInput
  ): HeapTraversalOutput {
    val previousTree = when (previousTraversal) {
      is InitialState -> null
      is HeapTraversalOutput -> previousTraversal.shortestPathTree
    }

    val firstTraversal = previousTree == null

    val secondTraversal = previousTraversal is FirstHeapTraversal
    val objectReferenceReader = referenceReaderFactory.createFor(graph)

    val roots = graph.groupRoots()
    enqueueRoots(previousTree, roots)

    val nodesPreviouslyGrowing = mutableListOf<Node>()

    while (queuesNotEmpty) {
      val node = poll()

      if (node.previousPathNode != null && node.previousPathNode.growing) {
        nodesPreviouslyGrowing += node
      }
      dequeuedNodes += node

      class ExpandedObject(
        val valueObjectId: Long,
        val nodeAndEdgeName: String,
        val isLowPriority: Boolean,
        val isLeafObject: Boolean
      )

      // Note: this is different from visitedSet.size(), which includes gc roots.
      var countOfVisitedObjectForCurrentNode = 0

      val edges = node.objectIds.flatMap { objectId ->
        // This is when we actually visit.
        val added = visitedSet.add(objectId)
        if (!added) {
          emptySequence()
        } else {
          countOfVisitedObjectForCurrentNode++
          if (node.isLeafObject) {
            emptySequence()
          } else {
            val heapObject = graph.findObjectById(objectId)
            val refs = objectReferenceReader.read(heapObject)
            refs.mapNotNull { reference ->
              // dominatorTree is updated prior to enqueueing, because that's where we have the
              // parent object id information. visitedSet is updated on dequeuing, because bumping
              // node priority would be complex when as we'd need to move object ids between nodes
              // rather than just move nodes.
              dominatorTree.updateDominated(
                objectId = reference.valueObjectId,
                parentObjectId = objectId
              )
              if (reference.valueObjectId in visitedSet) {
                null
              } else {
                val details = reference.lazyDetailsResolver.resolve()
                val refType = details.locationType.name
                val owningClassSimpleName =
                  graph.findObjectById(details.locationClassObjectId).asClass!!.simpleName
                val refName = if (details.locationType == ARRAY_ENTRY) "[x]" else details.name
                val referencedObjectName =
                  when (val referencedObject = graph.findObjectById(reference.valueObjectId)) {
                    is HeapClass -> "class ${referencedObject.name}"
                    is HeapInstance -> "instance of ${referencedObject.instanceClassName}"
                    is HeapObjectArray -> "array of ${referencedObject.arrayClassName}"
                    is HeapPrimitiveArray -> "array of ${referencedObject.primitiveType.name.lowercase()}"
                  }
                val nodeAndEdgeName =
                  "$refType ${owningClassSimpleName}.${refName} -> $referencedObjectName"
                ExpandedObject(
                  reference.valueObjectId, nodeAndEdgeName, reference.isLowPriority,
                  reference.isLeafObject
                )
              }
            }
          }
        }
      }.groupBy {
        it.nodeAndEdgeName + if (it.isLowPriority) "low-priority" else ""
      }

      if (countOfVisitedObjectForCurrentNode > 0) {
        val parent = node.shortestPathNode.parent!!
        val current = node.shortestPathNode
        parent.addChild(current)
        // First traversal, all nodes with children are growing.
        if (firstTraversal) {
          parent.growing = true
        }
        if (current.name == parent.name) {
          var linkedListStartNode = current
          while (linkedListStartNode.name == linkedListStartNode.parent!!.name) {
            // Never null, we don't expect to ever see "root" -> "root"
            linkedListStartNode = linkedListStartNode.parent!!
          }
          linkedListStartNode.selfObjectCount += countOfVisitedObjectForCurrentNode
        } else {
          current.selfObjectCount = countOfVisitedObjectForCurrentNode
        }
      }

      val previousNodeMap = node.previousPathNode?.let { shortestPathNode ->
        shortestPathNode.children.associateBy { it.name }
      }

      val edgesEnqueued = edges.map { (_, expandedObjects) ->
        val firstOfGroup = expandedObjects.first()
        val leafObject = expandedObjects.all { it.isLeafObject }
        val nodeAndEdgeName = firstOfGroup.nodeAndEdgeName
        val previousPathNode = if (previousNodeMap != null) {
          previousNodeMap[nodeAndEdgeName]
        } else {
          null
        }
        enqueue(
          parentPathNode = node.shortestPathNode,
          previousPathNode = previousPathNode,
          objectIds = expandedObjects.map { it.valueObjectId },
          nodeAndEdgeName = nodeAndEdgeName,
          isLowPriority = firstOfGroup.isLowPriority,
          isLeafObject = leafObject
        )
      }.count { enqueued -> enqueued }

      if (edgesEnqueued > 0) {
        node.shortestPathNode.createChildrenBackingList(edgesEnqueued)
      }
    }

    val growingNodes = if (previousTree != null) {
      val growingNodesForDominators = mutableListOf<Node>()
      // TODO Maybe don't create that list immediately, instead just add a flag, replacing "growing" with a bit
      // Actually we probably don't need to change anything from a data structure perspective, we have all we need locally in node.
      // This allows to get rid of the growingNodesForDominators list and just iterate on the full list.
      // We could also precompute the object id set needed for dominators as part of this loop.
      // Then we only need one more full iteration to go apply retained size.
      // TODO For the first traversal we'll have to compute the full retained size.. or at least
      // that of "growing" nodes i.e. non leaf nodes. Not sure what's most efficient, all vs a really large hashset.
      val growingNodes = nodesPreviouslyGrowing.mapNotNull { node ->
        val shortestPathNode = node.shortestPathNode

        // TODO Update, here node.previousPathNode is always non null.
        val growing = if (node.previousPathNode != null) {
          // Existing node. Growing if was growing (already true) and edges increased at least
          // detectedGrowth for at least one children which was already growing.
          // Why detectedGrowth? We perform N scenarios and only take N/detectedGrowth heap dumps
          // which avoids including any side effects of heap dumps in our leak detection.
          val previouslyGrowingChildren = if (secondTraversal) {
            node.previousPathNode.children.asSequence()
          } else {
            node.previousPathNode.growingChildrenArray?.asSequence()
          }

          val growingChildrenIncreases = if (previouslyGrowingChildren != null) {
            val previousGrowingChildrenByName =
              previouslyGrowingChildren.associateBy { it.name }

            shortestPathNode.children.mapNotNull { child ->
              val previousChild = previousGrowingChildrenByName[child.name]
              if (previousChild != null) {
                val childrenIncrease = child.selfObjectCount - previousChild.selfObjectCount
                if (childrenIncrease >= previousTraversal.scenarioLoopsPerGraph) {
                  child to childrenIncrease
                } else {
                  // Child not increasing.
                  // We found a previously growing child node but it didn't increase by enough
                  // this time.
                  null
                }
              } else {
                // Child not increasing.
                // Not the first traversal and we can't find a previously growing child node
                null
              }
            }.toList()
          } else {
            // no children increase
            null
          }

          if (!growingChildrenIncreases.isNullOrEmpty()) {
            val growingChildrenArray = growingChildrenIncreases.map { it.first }.toTypedArray()
            val growingChildrenIncreasesArray =
              growingChildrenIncreases.map { it.second }.toIntArray()
            shortestPathNode.growingChildrenArray = growingChildrenArray
            shortestPathNode.growingChildrenIncreasesArray = growingChildrenIncreasesArray
            // node growing
            true
          } else {
            // node not growing
            false
          }
        } else {
          // Not the first traversal and we can't find a previous node.
          false
        }
        if (growing) {
          // Mark as growing in the tree (useful for next iteration): if we conditioned setting this
          // to "parentGrowing", then adding an identical subgraph to an array would otherwise lead
          // to each distinct paths from roots to a node of a subgraph to be surfaced as a distinct
          // path.
          // We're traversing from parents to child. If we didn't mark here, then a path of
          // 3 growing nodes A->B->C would see B not marked as growing (because it's parent is)
          // and then C would end up being reported as really growing.
          shortestPathNode.growing = true

          val parentGrowing = (shortestPathNode.parent?.growing) ?: false

          val isGrowingRoot = !parentGrowing

          // Return in list of growing nodes.
          if (isGrowingRoot) {
            growingNodesForDominators += node
            shortestPathNode
          } else {
            null
          }
        } else {
          null
        }
      }

      // TODO we want to do several things:
      // We have the list of previously growing nodes
      // Identify nodes growing in this tree.
      // Identify nodes growing that don't have a parent growing. Those are the nodes to report,
      // and we'll want to build that list.
      // Get a list of those nodes to compute the hashset of all object ids, plus update them
      // after computing dominators.

      // if we don't build the list immediately, we can iterate on it, and check growing + parent
      // growing status.
      // 1. growingNodeObjectIds can be built as part of the original iteration rather than as a next step
      // 2. Instead of building growingNodesForDominators, we can iterate on nodesPreviouslyGrowing
      // directly and check if a node is a growing root to decide that the node should be updated.
      // 3. We should probably keep the mapNotNull that builds up growingNodes
      // 4. We should update those APIs to take the efficient hashset and return a LongObjectScatterMap

      val growingNodeObjectIds = MutableLongSet(growingNodesForDominators.size)
      growingNodesForDominators.forEach { node ->
        val objectIdsArray = node.objectIds.toLongArray()
        growingNodeObjectIds.addAll(objectIdsArray)
      }

      val objectSizeCalculator = AndroidObjectSizeCalculator(graph)
      val retainedMap =
        dominatorTree.computeRetainedSizes(growingNodeObjectIds, objectSizeCalculator)
      growingNodesForDominators.forEach { node ->
        val shortestPathNode = node.shortestPathNode
        var heapSize = ByteSize.ZERO
        var objectCount = 0
        for (objectId in node.objectIds) {
          val packed = retainedMap[objectId]
          val additionalByteSize = packed.unpackAsFirstInt
          val additionalObjectCount = packed.unpackAsSecondInt
          heapSize += additionalByteSize.bytes
          objectCount += additionalObjectCount
        }
        shortestPathNode.retainedOrNull = Retained(
          heapSize = heapSize,
          objectCount = objectCount
        )
        val previousRetained = node.previousPathNode?.retainedOrNull
        shortestPathNode.retainedIncreaseOrNull = if (previousRetained == null) {
          Retained.ZERO
        } else {
          Retained(
            heapSize - previousRetained.heapSize, objectCount - previousRetained.objectCount
          )
        }
      }
      growingNodes
    } else {
      null
    }

    return if (growingNodes == null) {
      check(previousTraversal is InitialState)
      // Iterating on last dequeued first means we'll get dominated first and progressively go
      // up the dominator tree.
      val objectSizeCalculator = AndroidObjectSizeCalculator(graph)
      // A map that stores two ints, size and count, in a single long value with bit packing.
      val retainedSizeAndCountMap = MutableLongLongMap(dequeuedNodes.size)
      for (node in dequeuedNodes.asReversed()) {
        var nodeRetainedSize = ByteSize.ZERO
        var nodeRetainedCount = 0

        for (objectId in node.objectIds) {
          val objectShallowSize = objectSizeCalculator.computeSize(objectId)

          val (retainedSize, retainedCount) = retainedSizeAndCountMap.increase(
            objectId, objectShallowSize, 1
          )
          val dominatorObjectId = dominatorTree[objectId]
          if (dominatorObjectId != ValueHolder.NULL_REFERENCE) {
            retainedSizeAndCountMap.increase(dominatorObjectId, retainedSize, retainedCount)
          }
          nodeRetainedSize += retainedSize.bytes
          nodeRetainedCount += retainedCount
        }

        if (node.shortestPathNode.growing) {
          node.shortestPathNode.retainedOrNull = Retained(
            heapSize = nodeRetainedSize,
            objectCount = nodeRetainedCount
          )
          // First traversal, can't compute an increase, nothing to diff on.
          node.shortestPathNode.retainedIncreaseOrNull = Retained.ZERO
        }
      }
      FirstHeapTraversal(tree, previousTraversal)
    } else {
      check(previousTraversal !is InitialState)
      HeapGrowthTraversal(
        previousTraversal.traversalCount + 1, tree, growingNodes, previousTraversal
      )
    }
  }

  private fun HeapGraph.groupRoots() =
    gcRootProvider.provideGcRoots(this).map { gcRootReference ->
      val name = "GcRoot(${gcRootReference.gcRoot::class.java.simpleName})"
      name to gcRootReference
    }
      // sort preserved
      .groupBy { it.first + if (it.second.isLowPriority) "low-priority" else "" }

  private fun TraversalState.poll(): Node {
    return if (!visitingLast && !toVisitQueue.isEmpty()) {
      toVisitQueue.poll()
    } else {
      visitingLast = true
      toVisitLastQueue.poll()
    }
  }

  private fun TraversalState.enqueueRoots(
    previousTree: ShortestPathObjectNode?,
    roots: Map<String, List<Pair<String, GcRootReference>>>
  ) {
    val previousTreeRootMap = previousTree?.let { tree ->
      tree.children.associateBy { it.name }
    }

    val enqueuedCount = roots.map { (_, gcRootReferences) ->
      val firstOfGroup = gcRootReferences.first()
      val nodeAndEdgeName = firstOfGroup.first
      val previousPathNode = if (previousTreeRootMap != null) {
        previousTreeRootMap[nodeAndEdgeName]
      } else {
        null
      }
      val objectIds = gcRootReferences.map { it.second.gcRoot.id }
      objectIds.forEach { objectId ->
        dominatorTree.updateDominatedAsRoot(objectId)
      }

      enqueue(
        parentPathNode = tree,
        previousPathNode = previousPathNode,
        objectIds = objectIds,
        nodeAndEdgeName = nodeAndEdgeName,
        isLowPriority = firstOfGroup.second.isLowPriority,
        isLeafObject = false
      )
    }.count { enqueued -> enqueued }
    tree.createChildrenBackingList(enqueuedCount)
  }

  private fun TraversalState.enqueue(
    parentPathNode: ShortestPathObjectNode,
    previousPathNode: ShortestPathObjectNode?,
    objectIds: List<Long>,
    nodeAndEdgeName: String,
    isLowPriority: Boolean,
    isLeafObject: Boolean
  ): Boolean {
    // TODO Maybe the filtering should happen at the callsite.
    // TODO we already filter visited on the traversal side. maybe crash?
    val filteredObjectIds = objectIds.filter { objectId ->
      objectId != ValueHolder.NULL_REFERENCE &&
        // note: we only update visitedSet once dequeued. This could lead
        // to duplicates in queue, but avoids having to bump priority of already
        // enqueued low priority nodes.
        objectId !in visitedSet
    }
      // Deduplicate object ids
      .toSet()

    if (filteredObjectIds.isEmpty()) {
      return false
    }

    val shortestPathNode =
      ShortestPathObjectNode(nodeAndEdgeName, parentPathNode)

    val node = Node(
      objectIds = filteredObjectIds,
      shortestPathNode = shortestPathNode,
      previousPathNode = previousPathNode,
      isLeafObject = isLeafObject
    )

    if (isLowPriority || visitingLast) {
      toVisitLastQueue += node
    } else {
      toVisitQueue += node
    }
    return true
  }

  private fun MutableLongLongMap.increase(
    objectId: Long,
    addedValue1: Int,
    addedValue2: Int,
  ): IntIntPair {
    val missing = ValueHolder.NULL_REFERENCE
    val packedValue = getOrDefault(objectId, ValueHolder.NULL_REFERENCE)
    return if (packedValue == missing) {
      val newPackedValue = ((addedValue1.toLong()) shl 32) or (addedValue2.toLong() and 0xffffffffL)
      put(objectId, newPackedValue)
      IntIntPair(addedValue1, addedValue2)
    } else {
      val existingValue1 = (packedValue shr 32).toInt()
      val existingValue2 = (packedValue and 0xFFFFFFFF).toInt()
      val newValue1 = existingValue1 + addedValue1
      val newValue2 = existingValue2 + addedValue2
      val newPackedValue = ((newValue1.toLong()) shl 32) or (newValue2.toLong() and 0xffffffffL)
      put(objectId, newPackedValue)
      IntIntPair(newValue1, newValue2)
    }
  }

  private data class Node(
    // All objects that you can reach through paths that all resolves to the same structure.
    val objectIds: Set<Long>,
    val shortestPathNode: ShortestPathObjectNode,
    val previousPathNode: ShortestPathObjectNode?,
    val isLeafObject: Boolean,
  )

  /**
   * This allows external modules to add factory methods for configured instances of this class as
   * extension functions of this companion object.
   */
  companion object
}
