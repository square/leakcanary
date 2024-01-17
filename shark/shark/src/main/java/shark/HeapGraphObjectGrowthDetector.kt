@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import java.util.ArrayDeque
import java.util.Deque
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.ReferenceLocationType.ARRAY_ENTRY
import shark.ReferenceReader.Factory
import shark.internal.hppc.LongScatterSet

class HeapGraphObjectGrowthDetector(
  private val gcRootProvider: GcRootProvider,
  private val referenceReaderFactory: Factory<HeapObject>,
) {

  fun findGrowingObjects(
    heapGraph: CloseableHeapGraph,
    scenarioLoops: Int,
    previousTraversal: InputHeapTraversal = NoHeapTraversalYet,
  ): HeapTraversal {
    val state = TraversalState()
    return heapGraph.use {
      state.traverseHeapDiffingShortestPaths(heapGraph, scenarioLoops, previousTraversal)
    }
  }

  private class TraversalState {
    var visitingLast = false

    /** Set of objects to visit */
    val toVisitQueue: Deque<Node> = ArrayDeque()

    /**
     * Paths to visit when [toVisitQueue] is empty.
     */
    val toVisitLastQueue: Deque<Node> = ArrayDeque()

    val visitedSet = LongScatterSet()

    val tree = ShortestPathObjectNode("root", null, newNode = false).apply {
      selfObjectCount = 1
    }
    val queuesNotEmpty: Boolean
      get() = toVisitQueue.isNotEmpty() || toVisitLastQueue.isNotEmpty()
  }

  @Suppress("ComplexMethod")
  private fun TraversalState.traverseHeapDiffingShortestPaths(
    graph: CloseableHeapGraph,
    detectedGrowth: Int,
    previousTraversal: InputHeapTraversal,
  ): HeapTraversal {

    // First iteration, all nodes are growing.
    if (previousTraversal is NoHeapTraversalYet) {
      tree.growing = true
    }

    val previousTree = when (previousTraversal) {
      NoHeapTraversalYet -> null
      is HeapTraversal -> previousTraversal.shortestPathTree
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
        val isLowPriority: Boolean
      )

      var visitedObjectCount = 0

      val edges = node.objectIds.flatMap { objectId ->
        // This is when we actually visit.
        val added = visitedSet.add(objectId)
        if (!added) {
          emptySequence()
        } else {
          visitedObjectCount++
          val heapObject = graph.findObjectById(objectId)
          val refs = objectReferenceReader.read(heapObject)
          refs.mapNotNull { reference ->
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
              ExpandedObject(reference.valueObjectId, nodeAndEdgeName, reference.isLowPriority)
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
        if (current.nodeAndEdgeName == parent.nodeAndEdgeName) {
          var linkedListStartNode = current
          while (linkedListStartNode.nodeAndEdgeName == linkedListStartNode.parent!!.nodeAndEdgeName) {
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
        shortestPathNode._children.associateBy { it.nodeAndEdgeName }
      }

      edges.forEach { (_, expandedObjects) ->
        val firstOfGroup = expandedObjects.first()
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
          isLowPriority = firstOfGroup.isLowPriority
        )
      }
    }

    val growingNodes = if (previousTree != null) {
      nodesMaybeGrowing.mapNotNull { node ->
        val shortestPathNode = node.shortestPathNode
        val growing = if (node.previousPathNode != null) {
          // Existing node. Growing if was growing (already true) and edges increased at least detectedGrowth.
          // Why detectedGrowth? We perform N scenarios and only take N/detectedGrowth heap dumps, which avoids including
          // any side effects of heap dumps in our leak detection.
          shortestPathNode.childrenObjectCount >= node.previousPathNode.childrenObjectCount + detectedGrowth
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
              node.previousPathNode._children.associateBy { it.nodeAndEdgeName }
            shortestPathNode._children.forEach { child ->
              val previousChild = previousChildrenByName[child.nodeAndEdgeName]
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
          // Mark as growing in the tree(useful for next iteration)
          shortestPathNode.growing = true
          // Return in list of growing nodes.
          shortestPathNode
        } else {
          null
        }
      }
    } else {
      null
    }

    return if (growingNodes == null) {
      check(previousTraversal is NoHeapTraversalYet)
      InitialHeapTraversal(tree)
    } else {
      check(previousTraversal !is NoHeapTraversalYet)
      val repeatedlyGrowingNodes =
        growingNodes.filter {
          val previouslyGrowing = !it.newNode
          val parentAlreadyReported = (it.parent?.growing) ?: false
          previouslyGrowing && !parentAlreadyReported
        }
      HeapTraversalWithDiff(tree, repeatedlyGrowingNodes)
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
      tree._children.associateBy { it.nodeAndEdgeName }
    }

    roots.forEach { (_, gcRootReferences) ->
      val firstOfGroup = gcRootReferences.first()
      val nodeAndEdgeName = firstOfGroup.first
      val previousPathNode = if (previousTreeRootMap != null) {
        previousTreeRootMap[nodeAndEdgeName]
      } else {
        null
      }
      enqueue(
        parentPathNode = tree,
        previousPathNode = previousPathNode,
        objectIds = gcRootReferences.map { it.second.gcRoot.id },
        nodeAndEdgeName = nodeAndEdgeName,
        isLowPriority = firstOfGroup.second.isLowPriority
      )
    }
  }

  private fun TraversalState.enqueue(
    parentPathNode: ShortestPathObjectNode,
    previousPathNode: ShortestPathObjectNode?,
    objectIds: List<Long>,
    nodeAndEdgeName: String,
    isLowPriority: Boolean,
  ) {
    // TODO Maybe the filtering should happen at the callsite.
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
      previousPathNode = previousPathNode
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
    val previousPathNode: ShortestPathObjectNode?
  )
}
