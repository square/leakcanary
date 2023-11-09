package shark

import java.io.File
import java.util.ArrayDeque
import java.util.Deque
import java.util.LinkedList
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ReferenceLocationType.ARRAY_ENTRY

class BleakDiffTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  // TODO Something is up with strings, this field doesn't even show up.
  val string = "Yo".trim()

  class Thing
  val leaky = mutableListOf<Thing>()
  val leakyLinkedList = LinkedList<Thing>()

  @Test
  fun bleakDiff() {
    val loopingScenario = {
      leaky += Thing()
      leakyLinkedList += Thing()
    }

    val dumps = mutableListOf<File>()
    for (i in 1..10) {
      loopingScenario()
      loopingScenario()
      dumps += dumpHeap()
    }

    val constantlyGrowingNodes = findNodesConstantlyGrowing(
      dumps = dumps,
      // We run the scenario twice per heap dumps, to ignore side effects of dumping the heap.
      detectedGrowth = 2
    )

    println(constantlyGrowingNodes.joinToString(separator = "##################\n") { leafNode ->
      leafNode.pathFromRootAsString()
    })
  }

  private fun findNodesConstantlyGrowing(
    dumps: MutableList<File>,
    detectedGrowth: Int
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
    return lastGrowingNodes!!
  }

  class ShortestPathNode(
    val nodeAndEdgeName: String,
    val parentPathNode: ShortestPathNode?,
  ) {
    val children = mutableListOf<ShortestPathNode>()
    var selfObjectCount = 0

    var growing = false

    val childrenObjectCount: Int
      get() = children.sumOf { it.selfObjectCount }

    fun print(maxLevel: Int) {
      val stack = mutableListOf<Pair<Int, ShortestPathNode>>()
      stack.add(0 to this)
      while (stack.isNotEmpty()) {
        val (level, node) = stack.removeLast()
        println(
          "  ".repeat(
            level
          ) + node.nodeAndEdgeName + "| growing: ${node.growing} | ${node.selfObjectCount} objects going to ${node.childrenObjectCount} children"
        )
        if (level + 1 <= maxLevel) {
          for (child in node.children.reversed()) {
            stack.add(level + 1 to child)
          }
        }
      }
    }

    fun pathFromRootAsString(): String {
      val pathFromRoot = mutableListOf<ShortestPathNode>()
      var unwindingNode: ShortestPathNode? = this
      while (unwindingNode != null) {
        pathFromRoot.add(0, unwindingNode)
        unwindingNode = unwindingNode.parentPathNode
      }
      val result = StringBuilder()
      pathFromRoot.forEachIndexed { index, pathNode ->
        result.append("  ".repeat(index))
        result.append(pathNode.nodeAndEdgeName)
        result.appendLine()
      }
      return result.toString()
    }
  }

  class Bfs(val graph: HeapGraph) {

    private val objectReferenceReader: ReferenceReader<HeapObject> =
      AndroidReferenceReaderFactory(defaultReferenceMatchers).createFor(graph)

    var visitingLast = false

    /** Set of objects to visit */
    val toVisitQueue: Deque<Node> = ArrayDeque()

    /**
     * Paths to visit when [toVisitQueue] is empty.
     */
    val toVisitLastQueue: Deque<Node> = ArrayDeque()

    // TODO LongScatterSet
    val visitedSet = mutableSetOf<Long>()

    val tree = ShortestPathNode("root", null).apply {
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

      val referenceMatchers = defaultReferenceMatchers
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
            // TODO For arrays change the name to not include the index.
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
          node.shortestPathNode.parentPathNode!!.children += node.shortestPathNode
          node.shortestPathNode.selfObjectCount = visitedObjectCount
          // First iteration, all nodes are growing.
          if (previousTree == null) {
            node.shortestPathNode.growing = true
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
          if (node.previousPathNode != null) {
            // Existing node. Growing if was growing (already true) and edges increased at least detectedGrowth.
            // Why detectedGrowth? We perform N scenarios and only take N/detectedGrowth heap dumps, which avoids including
            // any side effects of heap dumps in our leak detection.
            if (shortestPathNode.childrenObjectCount >= node.previousPathNode.childrenObjectCount + detectedGrowth) {
              shortestPathNode.growing = true
            }
          } else {
            // New node. Growing if parent is growing.
            // New node always have a parent.
            if (shortestPathNode.parentPathNode!!.growing) {
              shortestPathNode.growing = true
            }
          }
          if (shortestPathNode.growing) {
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

      val shortestPathNode = ShortestPathNode(nodeAndEdgeName, parentPathNode)

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

  private fun dumpHeap(): File {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "${System.nanoTime()}.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    return hprofFile
  }
}