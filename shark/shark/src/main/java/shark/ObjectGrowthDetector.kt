@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

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

    val computeRetainedHeapSize = previousTraversal.heapGraphCount?.let { heapGraphCount ->
      // Compute retained size for the prior to last and the last graphs.
      previousTraversal.traversalCount >= heapGraphCount - 1
    } ?: (previousTraversal.traversalCount > 1)

    // Estimate of how many objects we'll visit. This is a conservative estimate, we should always
    // visit more than that but this limits the number of early array growths.
    val estimatedVisitedObjects = (heapGraph.instanceCount / 2).coerceAtLeast(4)
    val state = TraversalState(
      estimatedVisitedObjects = estimatedVisitedObjects,
      computeRetainedHeapSize = computeRetainedHeapSize
    )
    return heapGraph.use {
      state.traverseHeapDiffingShortestPaths(
        heapGraph,
        previousTraversal
      )
    }.also { output ->
      if (output is HeapGrowthTraversal) {
        val scenarioCount = output.traversalCount * output.scenarioLoopsPerGraph
        SharkLog.d {
          "After $scenarioCount scenario iterations and ${output.traversalCount} heap dumps: " +
            "${output.growingObjects.size} growing nodes"
        }
      }
    }
  }

  private class TraversalState(
    estimatedVisitedObjects: Int,
    computeRetainedHeapSize: Boolean
  ) {
    var visitingLast = false

    /** Set of objects to visit */
    val toVisitQueue: Deque<Node> = ArrayDeque()

    /**
     * Paths to visit when [toVisitQueue] is empty.
     */
    val toVisitLastQueue: Deque<Node> = ArrayDeque()

    val visitedSet = LongScatterSet(estimatedVisitedObjects)
    val dominatorTree =
      if (computeRetainedHeapSize) DominatorTree(estimatedVisitedObjects) else null

    val tree = ShortestPathObjectNode("root", null, newNode = false).apply {
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

    // First iteration, all nodes are growing.
    if (previousTraversal is InitialState) {
      tree.growing = true
    }

    val previousTree = when (previousTraversal) {
      is InitialState -> null
      is HeapTraversalOutput -> previousTraversal.shortestPathTree
    }

    val objectReferenceReader = referenceReaderFactory.createFor(graph)

    val roots = graph.groupRoots()
    enqueueRoots(previousTree, roots)

    val nodesMaybeGrowing = mutableListOf<Node>()

    while (queuesNotEmpty) {
      val node = poll()

      if (previousTree != null) {
        if (node.previousPathNode == null) {
          // This is a new node, not seen in the previous iteration. If its parent is growing
          // then we'll consider this one as growing as well.
          nodesMaybeGrowing += node
        } else {
          if (node.previousPathNode.growing) {
            nodesMaybeGrowing += node
          }
        }
      }

      class ExpandedObject(
        val valueObjectId: Long,
        val nodeAndEdgeName: String,
        val isLowPriority: Boolean,
        val isLeafObject: Boolean
      )

      // Note: this is different from visitedSet.size(), which includes gc roots.
      var visitedObjectCount = 0

      val edges = node.objectIds.flatMap { objectId ->
        // This is when we actually visit.
        val added = visitedSet.add(objectId)
        if (!added) {
          emptySequence()
        } else {
          visitedObjectCount++
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
              dominatorTree?.updateDominated(
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

      if (visitedObjectCount > 0) {
        val parent = node.shortestPathNode.parent!!
        val current = node.shortestPathNode
        parent._children += current
        if (current.name == parent.name) {
          var linkedListStartNode = current
          while (linkedListStartNode.name == linkedListStartNode.parent!!.name) {
            // Never null, we don't expect to ever see "root" -> "root"
            linkedListStartNode = linkedListStartNode.parent!!
          }
          linkedListStartNode.selfObjectCount += visitedObjectCount
        } else {
          current.selfObjectCount = visitedObjectCount
        }
        // First iteration, all nodes are growing.
        if (previousTree == null) {
          current.growing = true
        }
      }

      val previousNodeMap = node.previousPathNode?.let { shortestPathNode ->
        shortestPathNode._children.associateBy { it.name }
      }

      edges.forEach { (_, expandedObjects) ->
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
      }
    }

    val growingNodes = if (previousTree != null) {
      val growingNodePairs = mutableListOf<Pair<Node, ShortestPathObjectNode>>()
      val growingNodes = nodesMaybeGrowing.mapNotNull { node ->
        val shortestPathNode = node.shortestPathNode
        val growing = if (node.previousPathNode != null) {
          // Existing node. Growing if was growing (already true) and edges increased at least detectedGrowth.
          // Why detectedGrowth? We perform N scenarios and only take N/detectedGrowth heap dumps, which avoids including
          // any side effects of heap dumps in our leak detection.
          shortestPathNode.childrenObjectCount >= node.previousPathNode.childrenObjectCount + previousTraversal.scenarioLoopsPerGraph
        } else {
          val parent = shortestPathNode.parent!!
          // New node. Growing if parent is growing.
          // New node always have a parent.
          // check for more than 0 because linked list structures will bubble their count up
          // and don't need to be marked as growing.
          parent.growing && shortestPathNode.selfObjectCount > 0
        }
        if (growing) {
          if (node.previousPathNode != null) {
            val previousChildrenByName =
              node.previousPathNode._children.associateBy { it.name }
            shortestPathNode._children.forEach { child ->
              val previousChild = previousChildrenByName[child.name]
              if (previousChild != null) {
                child.selfObjectCountIncrease =
                  child.selfObjectCount - previousChild.selfObjectCount
              } else {
                child.selfObjectCountIncrease = child.selfObjectCount
              }
            }
          } else {
            shortestPathNode._children.forEach { child ->
              child.selfObjectCountIncrease = child.selfObjectCount
            }
          }
          // Mark as growing in the tree (useful for next iteration)
          shortestPathNode.growing = true

          val previouslyGrowing = !shortestPathNode.newNode
          val parentAlreadyReported = (shortestPathNode.parent?.growing) ?: false

          val repeatedlyGrowingNode = previouslyGrowing && !parentAlreadyReported
          // Return in list of growing nodes.
          if (repeatedlyGrowingNode) {
            if (dominatorTree != null) {
              growingNodePairs += node to shortestPathNode
            }
            shortestPathNode
          } else {
            null
          }
        } else {
          null
        }
      }
      dominatorTree?.let { dominatorTree ->
        val growingNodeObjectIds = growingNodePairs.flatMapTo(LinkedHashSet()) { (node, _) ->
          node.objectIds
        }
        val objectSizeCalculator = AndroidObjectSizeCalculator(graph)
        val retainedMap =
          dominatorTree.computeRetainedSizes(growingNodeObjectIds, objectSizeCalculator)
        growingNodePairs.forEach { (node, shortestPathNode) ->
          var heapSize = ByteSize.ZERO
          var objectCount = 0
          for (objectId in node.objectIds) {
            val (additionalByteSize, additionalObjectCount) = retainedMap.getValue(objectId)
            heapSize += additionalByteSize.bytes
            objectCount += additionalObjectCount
          }
          shortestPathNode.retainedOrNull = Retained(
            heapSize = heapSize,
            objectCount = objectCount
          )
          val previousRetained = node.previousPathNode?.retainedOrNull
          shortestPathNode.retainedIncreaseOrNull = if (previousRetained == null) {
            Retained(ByteSize.ZERO, 0)
          } else {
            Retained(
              heapSize - previousRetained.heapSize, objectCount - previousRetained.objectCount
            )
          }
        }
      }
      growingNodes
    } else {
      null
    }

    return if (growingNodes == null) {
      check(previousTraversal is InitialState)
      FirstHeapTraversal(tree, previousTraversal)
    } else {
      check(previousTraversal !is InitialState)
      HeapGrowthTraversal(previousTraversal.traversalCount + 1, tree, growingNodes, previousTraversal)
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
      tree._children.associateBy { it.name }
    }

    roots.forEach { (_, gcRootReferences) ->
      val firstOfGroup = gcRootReferences.first()
      val nodeAndEdgeName = firstOfGroup.first
      val previousPathNode = if (previousTreeRootMap != null) {
        previousTreeRootMap[nodeAndEdgeName]
      } else {
        null
      }
      val objectIds = gcRootReferences.map { it.second.gcRoot.id }
      dominatorTree?.let {
        objectIds.forEach { objectId ->
          it.updateDominatedAsRoot(objectId)
        }
      }

      enqueue(
        parentPathNode = tree,
        previousPathNode = previousPathNode,
        objectIds = objectIds,
        nodeAndEdgeName = nodeAndEdgeName,
        isLowPriority = firstOfGroup.second.isLowPriority,
        isLeafObject = false
      )
    }
  }

  private fun TraversalState.enqueue(
    parentPathNode: ShortestPathObjectNode,
    previousPathNode: ShortestPathObjectNode?,
    objectIds: List<Long>,
    nodeAndEdgeName: String,
    isLowPriority: Boolean,
    isLeafObject: Boolean
  ) {
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
      return
    }

    val shortestPathNode =
      ShortestPathObjectNode(nodeAndEdgeName, parentPathNode, newNode = previousPathNode == null)

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
