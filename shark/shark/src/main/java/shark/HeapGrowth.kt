package shark

import java.io.File
import java.util.ArrayDeque
import java.util.Deque
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ReferenceLocationType.ARRAY_ENTRY

class HeapGrowth(
  private val dumpHeap: () -> File
) {

  fun assertNoRepeatedHeapGrowth(
    heapDumps: Int,
    scenarioLoopsPerDump: Int = 1,
    loopingScenario: () -> Unit
  ) {
    check(heapDumps > 1) {
      "There should be at least 2 heap dumps"
    }

    var lastGrowingNodes: List<ShortestPathNode>? = null
    var previousShortestPathTree: ShortestPathNode? = null
    for (i in 1..heapDumps) {
      for (j in 1..scenarioLoopsPerDump) {
        loopingScenario()
      }
      val heapDumpFile = dumpHeap()
      check(heapDumpFile.exists())
      heapDumpFile.openHeapGraph().use { graph ->
        val bfs = Bfs(graph)
        val (shortestPathTree, growingNodes) = bfs.computeShortestPathTreeDiff(
          previousShortestPathTree, scenarioLoopsPerDump
        )
        previousShortestPathTree = shortestPathTree
        lastGrowingNodes = growingNodes
      }
      if (lastGrowingNodes != null) {
        val constantlyGrowingNodeCount = lastGrowingNodes!!.filter { !it.newNode }.size
        val iterationCount = i * scenarioLoopsPerDump
        SharkLog.d { "After $iterationCount (+ $scenarioLoopsPerDump) iterations and heap dump $i/$heapDumps: $constantlyGrowingNodeCount growing nodes" }
        if (constantlyGrowingNodeCount == 0) {
          SharkLog.d { "Success, no more constantly growing nodes after ${i * scenarioLoopsPerDump} iterations" }
          return
        }
      }
    }

    val constantlyGrowingNodes = lastGrowingNodes!!.filter { !it.newNode }
    check(constantlyGrowingNodes.isNotEmpty())
    val resultString =
      constantlyGrowingNodes.joinToString(separator = "##################\n") { leafNode ->
        leafNode.pathFromRootAsString()
      }
    throw AssertionError("Repeated heap growth detected, leak roots:\n$resultString")
  }

  fun findNodesConstantlyGrowing(
    dumps: MutableList<File>,
    detectedGrowth: Int = 1
  ): List<ShortestPathNode> {
    check(dumps.size > 1) {
      "There should be at least 2 heap dumps"
    }
    var lastGrowingNodes: List<ShortestPathNode>? = null
    var previousShortestPathTree: ShortestPathNode? = null
    for (dump in dumps) {
      dump.openHeapGraph().use { graph ->
        val bfs = Bfs(graph)
        val (shortestPathTree, growingNodes) = bfs.computeShortestPathTreeDiff(
          previousShortestPathTree, detectedGrowth
        )
        previousShortestPathTree = shortestPathTree
        lastGrowingNodes = growingNodes
      }
    }
    return lastGrowingNodes!!.filter { !it.newNode }
  }

  class ShortestPathNode(
    val nodeAndEdgeName: String,
    val parentPathNode: ShortestPathNode?,
    val newNode: Boolean
  ) {
    val children = mutableListOf<ShortestPathNode>()
    var selfObjectCount = 0
    var selfObjectCountIncrease = 0

    var growing = false

    val childrenObjectCount: Int
      get() = children.sumOf { it.selfObjectCount }

    fun pathFromRootAsString(): String {
      val pathFromRoot = mutableListOf<ShortestPathNode>()
      var unwindingNode: ShortestPathNode? = this
      while (unwindingNode != null) {
        pathFromRoot.add(0, unwindingNode)
        unwindingNode = unwindingNode.parentPathNode
      }
      val pathAfterRoot = pathFromRoot.drop(1)
      val result = StringBuilder()
      result.append("┬───").appendLine()
      pathAfterRoot.forEachIndexed { index, pathNode ->
        if (index == 0) {
          result.append("│ ")
        } else if (index < pathAfterRoot.lastIndex) {
          result.append("├─")
        } else {
          result.append("╰→")
        }
        result.append(pathNode.nodeAndEdgeName)
        result.append(" (")
        result.append(pathNode.selfObjectCount)
        result.append(" objects)")
        if (index == pathAfterRoot.lastIndex) {
          result.appendLine()
          result.append("    Children:")
          result.appendLine()
          val childrenByMostIncreasedFirst =
            pathNode.children.sortedBy { -it.selfObjectCountIncrease }
          result.append(
            childrenByMostIncreasedFirst.joinToString(
              separator = "\n",
              postfix = "\n"
            ) { child ->
              "    ${child.selfObjectCount} objects (${child.selfObjectCountIncrease} new): ${child.nodeAndEdgeName}"
            })
        } else {
          result.appendLine()
          result.append("│ ").appendLine()
        }
      }
      return result.toString()
    }
  }

  class Bfs(val graph: HeapGraph) {

    private val objectReferenceReader: ReferenceReader<HeapObject> =
      AndroidReferenceReaderFactory(JdkReferenceMatchers.defaults).createFor(graph)

    var visitingLast = false

    /** Set of objects to visit */
    val toVisitQueue: Deque<Node> = ArrayDeque()

    /**
     * Paths to visit when [toVisitQueue] is empty.
     */
    val toVisitLastQueue: Deque<Node> = ArrayDeque()

    // TODO LongScatterSet
    val visitedSet = mutableSetOf<Long>()

    val tree = ShortestPathNode("root", null, newNode = false).apply {
      selfObjectCount = 1
    }

    val queuesNotEmpty: Boolean
      get() = toVisitQueue.isNotEmpty() || toVisitLastQueue.isNotEmpty()

    fun computeShortestPathTreeDiff(
      previousTree: ShortestPathNode?,
      detectedGrowth: Int
    ): Pair<ShortestPathNode, List<ShortestPathNode>?> {
      // First iteration, all nodes are growing.
      if (previousTree == null) {
        tree.growing = true
      }

      val referenceMatchers = JdkReferenceMatchers.defaults
      val gcRootProvider = MatchingGcRootProvider(referenceMatchers)

      val roots = groupRoots(gcRootProvider)
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
          try {
            it.nodeAndEdgeName + if (it.isLowPriority) "low-priority" else ""
          } catch (e: Throwable) {
            println("Wow that's unexpected trying to add to ${it.nodeAndEdgeName}")
            e.printStackTrace()
            throw e
          }
        }

        if (visitedObjectCount > 0) {
          val parent = node.shortestPathNode.parentPathNode!!
          val current = node.shortestPathNode
          parent.children += current
          if (current.nodeAndEdgeName == parent.nodeAndEdgeName) {
            var linkedListStartNode = current
            while (linkedListStartNode.nodeAndEdgeName == linkedListStartNode.parentPathNode!!.nodeAndEdgeName) {
              // Never null, we don't expect to ever see "root" -> "root"
              linkedListStartNode = linkedListStartNode.parentPathNode!!
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
          shortestPathNode.children.associateBy { it.nodeAndEdgeName }
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
            val parent = shortestPathNode.parentPathNode!!
            // New node. Growing if parent is growing.
            // New node always have a parent.
            // check for more than 0 because linked list structures will bubble their count up
            // and don't need to be marked as growing.
            parent.growing && shortestPathNode.selfObjectCount > 0
          }
          if (growing) {
            if (node.previousPathNode != null) {
              val previousChildrenByName =
                node.previousPathNode.children.associateBy { it.nodeAndEdgeName }
              shortestPathNode.children.forEach { child ->
                val previousChild = previousChildrenByName[child.nodeAndEdgeName]
                if (previousChild != null) {
                  child.selfObjectCountIncrease =
                    child.selfObjectCount - previousChild.selfObjectCount
                } else {
                  child.selfObjectCountIncrease = child.selfObjectCount
                }
              }
            } else {
              shortestPathNode.children.forEach { child ->
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

      return tree to growingNodes
    }

    private fun groupRoots(
      gcRootProvider: MatchingGcRootProvider,
    ) = gcRootProvider.provideGcRoots(graph).map { gcRootReference ->
      val name = "GcRoot(${gcRootReference.gcRoot::class.java.simpleName})"
      name to gcRootReference
    }
      // sort preserved
      .groupBy { it.first + if (it.second.isLowPriority) "low-priority" else "" }

    private fun poll(): Node {
      return if (!visitingLast && !toVisitQueue.isEmpty()) {
        toVisitQueue.poll()
      } else {
        visitingLast = true
        toVisitLastQueue.poll()
      }
    }

    fun enqueueRoots(
      previousTree: ShortestPathNode?,
      roots: Map<String, List<Pair<String, GcRootReference>>>
    ) {
      val previousTreeRootMap = previousTree?.let { tree ->
        tree.children.associateBy { it.nodeAndEdgeName }
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

    fun enqueue(
      parentPathNode: ShortestPathNode,
      previousPathNode: ShortestPathNode?,
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
        ShortestPathNode(nodeAndEdgeName, parentPathNode, newNode = previousPathNode == null)

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
  }

  data class Node(
    // All objects that you can reach through paths that all resolves to the same structure.
    val objectIds: Set<Long>,
    val shortestPathNode: ShortestPathNode,
    val previousPathNode: ShortestPathNode?
  )
}
