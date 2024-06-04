@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import androidx.collection.MutableLongList
import androidx.collection.MutableLongLongMap
import androidx.collection.MutableLongSet
import androidx.collection.mutableLongListOf
import java.util.ArrayDeque
import java.util.Deque
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.ReferenceLocationType.ARRAY_ENTRY
import shark.internal.unpackAsFirstInt
import shark.internal.unpackAsSecondInt

/**
 * Looks for objects that have grown in outgoing references in a new heap dump compared to a
 * previous heap dump by diffing heap traversals.
 */
class ObjectGrowthDetector(
  private val gcRootProvider: GcRootProvider,
  private val referenceReaderFactory: ReferenceReader.Factory<HeapObject>,
) {

  fun findGrowingObjects(
    heapGraph: HeapGraph,
    previousTraversal: HeapTraversalInput = InitialState(),
  ): HeapTraversalOutput {
    check(previousTraversal !is HeapDiff || previousTraversal.isGrowing) {
      "Previous HeapGrowth traversal was not growing, there's no reason to run this again. " +
        "previousTraversal:$previousTraversal"
    }

    // Estimate of how many objects we'll visit. This is a conservative estimate, we should always
    // visit more than that but this limits the number of early array growths.
    val estimatedVisitedObjects = (heapGraph.instanceCount / 2).coerceAtLeast(4)
    val state = TraversalState(estimatedVisitedObjects = estimatedVisitedObjects)
    return state.traverseHeapDiffingShortestPaths(
      heapGraph,
      previousTraversal
    )
  }

  // data class to be a properly implemented key.
  private data class EdgeKey(
    val nodeAndEdgeName: String,
    val isLowPriority: Boolean
  )

  private class Edge(
    val nonVisitedDistinctObjectIds: MutableLongList,
    var isLeafObject: Boolean
  )

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

    val visitedSet = MutableLongSet(estimatedVisitedObjects)

    // Not using estimatedVisitedObjects because there could be a lot less nodes than objects.
    // This is a list because order matters.
    val dequeuedNodes = mutableListOf<DequeuedNode>()
    val dominatorTree = DominatorTree(estimatedVisitedObjects)

    val tree = ShortestPathObjectNode("root", null).apply {
      selfObjectCount = 1
    }
    val queuesNotEmpty: Boolean
      get() = toVisitQueue.isNotEmpty() || toVisitLastQueue.isNotEmpty()
  }

  @Suppress("ComplexMethod")
  private fun TraversalState.traverseHeapDiffingShortestPaths(
    graph: HeapGraph,
    previousTraversal: HeapTraversalInput
  ): HeapTraversalOutput {
    val previousTree = when (previousTraversal) {
      is InitialState -> null
      is HeapTraversalOutput -> previousTraversal.shortestPathTree
    }

    val firstTraversal = previousTree == null

    val secondTraversal = previousTraversal is FirstHeapTraversal
    val objectReferenceReader = referenceReaderFactory.createFor(graph)

    enqueueRoots(previousTree, graph)

    while (queuesNotEmpty) {
      val node = poll()

      val dequeuedNode = DequeuedNode(node)
      dequeuedNodes.add(dequeuedNode)
      val current = dequeuedNode.shortestPathNode

      // Note: this is different from visitedSet.size(), which includes gc roots.
      var countOfVisitedObjectForCurrentNode = 0

      val edgesByNodeName = mutableMapOf<EdgeKey, Edge>()
      // Each object we've found for that node is returning a set of edges.
      node.objectIds.forEach exploreObjectEdges@{ objectId ->
        // This is when we actually visit.
        val added = visitedSet.add(objectId)

        if (!added) {
          return@exploreObjectEdges
        }

        countOfVisitedObjectForCurrentNode++

        if (node.isLeafObject) {
          return@exploreObjectEdges
        }

        val heapObject = graph.findObjectById(objectId)
        val refs = objectReferenceReader.read(heapObject)
        refs.forEach recordEdge@{ reference ->
          // dominatorTree is updated prior to enqueueing, because that's where we have the
          // parent object id information. visitedSet is updated on dequeuing, because bumping
          // node priority would be complex when as we'd need to move object ids between nodes
          // rather than just move nodes.
          dominatorTree.updateDominated(
            objectId = reference.valueObjectId,
            parentObjectId = objectId
          )
          // note: we only update visitedSet once dequeued. This could lead
          // to duplicates in queue, but avoids having to bump priority of already
          // enqueued low priority nodes.
          if (reference.valueObjectId in visitedSet) {
            return@recordEdge
          }
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

          val edgeKey = EdgeKey(nodeAndEdgeName, reference.isLowPriority)

          val edge = edgesByNodeName[edgeKey]
          if (edge == null) {
            edgesByNodeName[edgeKey] = Edge(
              nonVisitedDistinctObjectIds = mutableLongListOf(reference.valueObjectId),
              isLeafObject = reference.isLeafObject,
            )
          } else {
            // node is leaf object if all objects in node are leaf objects.
            edge.isLeafObject = edge.isLeafObject && reference.isLeafObject
            // Make it distinct
            if (reference.valueObjectId !in edge.nonVisitedDistinctObjectIds) {
              edge.nonVisitedDistinctObjectIds += reference.valueObjectId
            }
          }
        }
      }

      if (countOfVisitedObjectForCurrentNode > 0) {
        val parent = node.parentPathNode
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

      val previousNodeChildrenMapOrNull = node.previousPathNode?.let { previousPathNode ->
        previousPathNode.children.associateBy { it.name }
      }

      val edgesEnqueued = edgesByNodeName.count { (edgeKey, edge) ->
        val previousPathNodeChildOrNull =
          previousNodeChildrenMapOrNull?.get(edgeKey.nodeAndEdgeName)
        val nonVisitedDistinctObjectIdsArray = LongArray(edge.nonVisitedDistinctObjectIds.size)
        edge.nonVisitedDistinctObjectIds.forEachIndexed { index, objectId ->
          nonVisitedDistinctObjectIdsArray[index] = objectId
        }

        enqueue(
          parentPathNode = current,
          previousPathNode = previousPathNodeChildOrNull,
          objectIds = nonVisitedDistinctObjectIdsArray,
          nodeAndEdgeName = edgeKey.nodeAndEdgeName,
          isLowPriority = edgeKey.isLowPriority,
          isLeafObject = edge.isLeafObject
        )
        return@count true
      }

      if (edgesEnqueued > 0) {
        current.createChildrenBackingList(edgesEnqueued)
      }
    }

    return if (previousTraversal is InitialState) {
      // Iterating on last dequeued first means we'll get dominated first and progressively go
      // up the dominator tree.
      val objectSizeCalculator = AndroidObjectSizeCalculator(graph)
      // A map that stores two ints, size and count, in a single long value with bit packing.
      val retainedSizeAndCountMap = MutableLongLongMap(dequeuedNodes.size)
      for (node in dequeuedNodes.asReversed()) {
        var nodeRetainedSize = ZERO_BYTES
        var nodeRetainedCount = 0

        for (objectId in node.objectIds) {
          val objectShallowSize = objectSizeCalculator.computeSize(objectId)

          val packedSizeAndCount = retainedSizeAndCountMap.increase(
            objectId, objectShallowSize, 1
          )

          val retainedSize = packedSizeAndCount.unpackAsFirstInt
          val retainedCount = packedSizeAndCount.unpackAsSecondInt

          val dominatorObjectId = dominatorTree[objectId]
          if (dominatorObjectId != ValueHolder.NULL_REFERENCE) {
            retainedSizeAndCountMap.increase(dominatorObjectId, retainedSize, retainedCount)
          }
          nodeRetainedSize += retainedSize.bytes
          nodeRetainedCount += retainedCount
        }

        if (node.shortestPathNode.growing) {
          node.shortestPathNode.retained = Retained(
            heapSize = nodeRetainedSize,
            objectCount = nodeRetainedCount
          )
          // First traversal, can't compute an increase, nothing to diff on.
          node.shortestPathNode.retainedIncrease = ZERO_RETAINED
        }
      }
      FirstHeapTraversal(tree, previousTraversal)
    } else {
      val reportedGrowingNodeObjectIdsForRetainedSize = MutableLongSet()
      // Marks node as "growing" if we can find a corresponding previous node that was growing and
      // we see at least one child node that increased its number of objects over our threshold.
      val reportedGrowingNodes = dequeuedNodes.mapNotNull reportedGrowingNodeOrNull@{ node ->
        val previousPathNode = node.previousPathNode
        // if node wasn't previously growing, skip it.
        if (previousPathNode == null || !previousPathNode.growing) {
          return@reportedGrowingNodeOrNull null
        }

        val shortestPathNode = node.shortestPathNode

        // Existing node. Growing if was growing (already true) and edges increased at least
        // detectedGrowth for at least one children which was already growing.
        // Why detectedGrowth? We perform N scenarios and only take N/detectedGrowth heap dumps
        // which avoids including any side effects of heap dumps in our leak detection.
        val previouslyGrowingChildren = if (secondTraversal) {
          previousPathNode.children.asSequence()
        } else {
          previousPathNode.growingChildrenArray?.asSequence()
        }

        // Node had no previously growing children, skip.
        if (previouslyGrowingChildren == null) {
          return@reportedGrowingNodeOrNull null
        }

        val previousGrowingChildrenByName =
          previouslyGrowingChildren.associateBy { it.name }

        // Set size to max possible
        val growingChildren = ArrayList<ShortestPathObjectNode>(shortestPathNode.children.size)
        val growingChildrenIncreases = IntArray(shortestPathNode.children.size)
        shortestPathNode.children.forEach growingChildren@{ child ->
          val previousChild = previousGrowingChildrenByName[child.name]
            ?: return@growingChildren
          val childrenIncrease = child.selfObjectCount - previousChild.selfObjectCount

          if (childrenIncrease < previousTraversal.scenarioLoopsPerGraph) {
            // Child stopped growing
            return@growingChildren
          }

          growingChildrenIncreases[growingChildren.size] = childrenIncrease
          growingChildren += child
        }

        // No child grew beyond threshold, skip.
        if (growingChildren.isEmpty()) {
          return@reportedGrowingNodeOrNull null
        }

        shortestPathNode.growingChildrenArray = growingChildren.toTypedArray()
        shortestPathNode.growingChildrenIncreasesArray =
          growingChildrenIncreases.copyOf(growingChildren.size)

        // Mark as growing in the tree (useful for next iteration): if we conditioned setting this
        // to "parentGrowing", then adding an identical subgraph to an array would otherwise lead
        // to each distinct paths from roots to a node of a subgraph to be surfaced as a distinct
        // path.
        // We're traversing from parents to child. If we didn't mark here, then a path of
        // 3 growing nodes A->B->C would see B not marked as growing (because it's parent is)
        // and then C would end up being reported as really growing.
        shortestPathNode.growing = true

        val parentGrowing = (shortestPathNode.parent?.growing) ?: false

        // Parent already growing, there's no need to report its child node as a growing node.
        if (parentGrowing) {
          return@reportedGrowingNodeOrNull null
        }

        node.objectIds.forEach { objectId ->
          reportedGrowingNodeObjectIdsForRetainedSize.add(objectId)
        }
        return@reportedGrowingNodeOrNull shortestPathNode
      }
      val objectSizeCalculator = AndroidObjectSizeCalculator(graph)
      val retainedMap = dominatorTree.computeRetainedSizes(
        reportedGrowingNodeObjectIdsForRetainedSize, objectSizeCalculator
      )
      dequeuedNodes.forEach reportedGrowingNodeRetainedSize@{ node ->
        val shortestPathNode = node.shortestPathNode
        // If not growing, or growing but with a parent that's growing, skip.
        if (!shortestPathNode.growing ||
          (shortestPathNode.parent != null && shortestPathNode.parent.growing)
        ) {
          return@reportedGrowingNodeRetainedSize
        }

        var heapSize = ZERO_BYTES
        var objectCount = 0
        for (objectId in node.objectIds) {
          val packed = retainedMap[objectId]
          val additionalByteSize = packed.unpackAsFirstInt
          val additionalObjectCount = packed.unpackAsSecondInt
          heapSize += additionalByteSize.bytes
          objectCount += additionalObjectCount
        }
        shortestPathNode.retained = Retained(
          heapSize = heapSize,
          objectCount = objectCount
        )
        val previousRetained = node.previousPathNode?.retained ?: UNKNOWN_RETAINED
        shortestPathNode.retainedIncrease = if (previousRetained.isUnknown) {
          ZERO_RETAINED
        } else {
          Retained(
            heapSize - previousRetained.heapSize, objectCount - previousRetained.objectCount
          )
        }
      }
      HeapDiff(
        previousTraversal.traversalCount + 1, tree, reportedGrowingNodes, previousTraversal
      )
    }
  }

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
    heapGraph: HeapGraph
  ) {
    val previousTreeRootMap = previousTree?.let { tree ->
      tree.children.associateBy { it.name }
    }

    val edgesByNodeName = mutableMapOf<EdgeKey, MutableLongList>()
    gcRootProvider.provideGcRoots(heapGraph).forEach { gcRootReference ->
      val objectId = gcRootReference.gcRoot.id
      if (objectId == ValueHolder.NULL_REFERENCE) {
        return@forEach
      }

      val name = "GcRoot(${gcRootReference.gcRoot::class.java.simpleName})"
      val edgeKey = EdgeKey(name, gcRootReference.isLowPriority)

      val edgeObjectIds = edgesByNodeName[edgeKey]
      if (edgeObjectIds == null) {
        edgesByNodeName[edgeKey] = mutableLongListOf(objectId)
      } else {
        if (objectId !in edgeObjectIds) {
          edgeObjectIds += objectId
        }
      }
    }
    val enqueuedCount = edgesByNodeName.count { (edgeKey, edgeObjectIds) ->
      val previousPathNode = previousTreeRootMap?.get(edgeKey.nodeAndEdgeName)

      edgeObjectIds.forEach { objectId ->
        dominatorTree.updateDominatedAsRoot(objectId)
      }

      val edgeObjectIdsArray = LongArray(edgeObjectIds.size)

      edgeObjectIds.forEachIndexed { index, objectId ->
        edgeObjectIdsArray[index] = objectId
      }
      enqueue(
        parentPathNode = tree,
        previousPathNode = previousPathNode,
        objectIds = edgeObjectIdsArray,
        nodeAndEdgeName = edgeKey.nodeAndEdgeName,
        isLowPriority = edgeKey.isLowPriority,
        isLeafObject = false
      )
      return@count true
    }
    tree.createChildrenBackingList(enqueuedCount)
  }

  private fun TraversalState.enqueue(
    parentPathNode: ShortestPathObjectNode,
    previousPathNode: ShortestPathObjectNode?,
    objectIds: LongArray,
    nodeAndEdgeName: String,
    isLowPriority: Boolean,
    isLeafObject: Boolean
  ) {
    val node = Node(
      objectIds = objectIds,
      parentPathNode = parentPathNode,
      nodeAndEdgeName = nodeAndEdgeName,
      previousPathNode = previousPathNode,
      isLeafObject = isLeafObject
    )

    if (isLowPriority || visitingLast) {
      toVisitLastQueue += node
    } else {
      toVisitQueue += node
    }
  }

  private fun MutableLongLongMap.increase(
    objectId: Long,
    addedValue1: Int,
    addedValue2: Int,
  ): Long {
    val missing = ValueHolder.NULL_REFERENCE
    val packedValue = getOrDefault(objectId, ValueHolder.NULL_REFERENCE)
    return if (packedValue == missing) {
      val newPackedValue = ((addedValue1.toLong()) shl 32) or (addedValue2.toLong() and 0xffffffffL)
      put(objectId, newPackedValue)
      newPackedValue
    } else {
      val existingValue1 = (packedValue shr 32).toInt()
      val existingValue2 = (packedValue and 0xFFFFFFFF).toInt()
      val newValue1 = existingValue1 + addedValue1
      val newValue2 = existingValue2 + addedValue2
      val newPackedValue = ((newValue1.toLong()) shl 32) or (newValue2.toLong() and 0xffffffffL)
      put(objectId, newPackedValue)
      newPackedValue
    }
  }

  private class Node(
    // All objects that you can reach through paths that all resolves to the same structure.
    val objectIds: LongArray,
    val parentPathNode: ShortestPathObjectNode,
    val nodeAndEdgeName: String,
    val previousPathNode: ShortestPathObjectNode?,
    val isLeafObject: Boolean,
  )

  private class DequeuedNode(
    node: Node
  ) {
    // All objects that you can reach through paths that all resolves to the same structure.
    val objectIds = node.objectIds
    val shortestPathNode = ShortestPathObjectNode(node.nodeAndEdgeName, node.parentPathNode)
    val previousPathNode = node.previousPathNode
  }

  /**
   * This allows external modules to add factory methods for configured instances of this class as
   * extension functions of this companion object.
   */
  companion object
}
