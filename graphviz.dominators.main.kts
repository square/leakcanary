#!/usr/bin/env kotlin -language-version 1.9

// Make sure you run "brew install kotlin graphviz" first.

@file:Repository("https://repo.maven.apache.org/maven2/")
@file:Repository("https://dl.google.com/dl/android/maven2/")
@file:DependsOn("com.squareup.leakcanary:shark-android:3.0-alpha-8")
@file:DependsOn("androidx.collection:collection-jvm:1.4.0")

import androidx.collection.IntList
import androidx.collection.MutableIntList
import java.io.File
import java.io.PrintWriter
import java.util.Stack
import kotlin.math.max
import shark.ActualMatchingReferenceReaderFactory
import shark.AndroidObjectSizeCalculator
import shark.GcRoot.JavaFrame
import shark.HprofHeapGraph.Companion.openHeapGraph

val heapDumpFile = File("/Users/py/Desktop/memory-20240919T161101.hprof")

heapDumpFile.openHeapGraph().use { graph ->
  val referenceReader = ActualMatchingReferenceReaderFactory(emptyList()).createFor(graph)

  val traversalRoots = graph.gcRoots
    // Exclude Java local references
    .filter { it !is JavaFrame }
    .map { HeapNode(it.id) }.toSet()

  val (sortedHeapNodes, immediateDominators) = with(LinkEvalDominators()) {
    computeDominators(traversalRoots) { (sourceObjectId) ->
      val sourceObject = graph.findObjectById(sourceObjectId)
      referenceReader.read(sourceObject).map { reference ->
        HeapNode(reference.valueObjectId)
      }
    }
  }

  val objectIdsInTopologicalOrder = sortedHeapNodes.map { it?.objectId }

  val objectSizeCalculator = AndroidObjectSizeCalculator(graph)
  val dominatorsByObjectId = objectIdsInTopologicalOrder.mapNotNull { objectIdOrNull ->
    objectIdOrNull?.let { objectId ->
      val name = graph.findObjectById(objectId).toString()
      val shallowSize = objectSizeCalculator.computeSize(objectId)
      objectId to DominatorObject(name, shallowSize = shallowSize)
    }
  }.toMap()

  for (dominatedObjectIndex in objectIdsInTopologicalOrder.indices.reversed()) {
    immediateDominators[dominatedObjectIndex]?.let { (dominatorObjectId) ->
      val dominatedObjectId = objectIdsInTopologicalOrder[dominatedObjectIndex]!!
      val dominator = dominatorsByObjectId.getValue(dominatorObjectId)
      val dominated = dominatorsByObjectId.getValue(dominatedObjectId)
      dominator.retainedSize += dominated.retainedSize
      dominator.dominatedNodes += dominated
      dominated.parent = dominator
    }
  }

  var maxDepth = 0
  dominatorsByObjectId.values.forEach { dominator ->
    var parent = dominator.parent
    var depth = 1
    while(parent != null) {
      parent = parent.parent
      depth++
    }
    dominator.depth = depth
    maxDepth = max(maxDepth, depth)
  }

  val rootDominators = dominatorsByObjectId.values.filter { it.parent == null }

  val shallowSizeSum = dominatorsByObjectId.values.sumOf { it.shallowSize }
  val retainedSum = rootDominators.sumOf { it.retainedSize }
  val traversalRootIds = traversalRoots.map { it.objectId }
  val gcRootShallowSizeSum = dominatorsByObjectId.entries.filter { (key, value) ->
    value.parent == null && key in traversalRootIds
  }.sumOf { it.value.shallowSize }
  val rootDominatorShallowSize = rootDominators.sumOf { it.shallowSize }

  println("Found ${rootDominators.size} root dominators, for ${traversalRoots.size} gc roots, max depth $maxDepth, shallowSizeSum:$shallowSizeSum retainedSum:$retainedSum , gcRootShallowSizeSum:$gcRootShallowSizeSum rootDominatorShallowSize:$rootDominatorShallowSize")

  val csv = File(heapDumpFile.parent, "${heapDumpFile.nameWithoutExtension}-whole-graph.csv")
  csv.printWriter().use { writer ->
    with(writer) {
      println("\"Child\",\"Parent\",\"Value\",\"Depth\"")
      println("\"root\",\"\",\"${shallowSizeSum}\",0")
      rootDominators.forEach { rootDominator ->
        printDominatorCsvRow(rootDominator)
      }
    }
  }
  println("Done generating ${csv.absolutePath}")
}


data class HeapNode(val objectId: Long)

class DominatorObject(
  val name: String,
  val shallowSize: Int
) {
  var parent: DominatorObject? = null
  val dominatedNodes = mutableListOf<DominatorObject>()
  var retainedSize = shallowSize
  var depth = 0
}

fun PrintWriter.printDominatorCsvRow(
  node: DominatorObject,
) {
  val parent = node.parent
  if (parent == null) {
    println("\"${node.name}\",\"root\",\"${node.retainedSize}\", ${node.depth}")
  } else {
    println("\"${node.name}\",\"${parent.name}\",\"${node.retainedSize}\", ${node.depth}")
  }
  for (child in node.dominatedNodes) {
    printDominatorCsvRow(child)
  }
}

/**
 * Based on https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:perflib/src/main/java/com/android/tools/perflib/heap/analysis/LinkEvalDominators.kt;l=36;drc=499fa43009666c0f0a686d8e21722dbea8b2ecf0
 * Computes dominators based on the union-find data structure with path compression and linking by
 * size. Using description found in: http://adambuchsbaum.com/papers/dom-toplas.pdf which is based
 * on a copy of the paper available at:
 * http://www.cc.gatech.edu/~harrold/6340/cs6340_fall2009/Readings/lengauer91jul.pdf
 */
class LinkEvalDominators {

  /**
   * @return 2 parallel lists of each node and its immediate dominator,
   *         with `null` being the auxiliary root.
   */
  fun <T> computeDominators(
    roots: Set<T>,
    next: (T) -> Sequence<T>
  ): Result<T> {
    // Step 1 of paper.
    // Number the instances by their DFS-traversal order and record each one's parent in the DFS
    // tree.
    // Also gather predecessors and initialize semi-dominators in the same pass.
    val (instances, parents, preds) = computeIndicesAndParents(roots, next)

    val semis = IntArray(instances.size) { it }
    // For each node, list of the nodes it semi-dominates.
    val buckets = Array(instances.size) {
      // This was using Trove4j's default capacity, 10 entries.
      MutableIntList(10)
    }
    val doms = IntArray(instances.size)
    val ancestors = IntArray(instances.size) { INVALID_ANCESTOR }
    val labels = IntArray(instances.size) { it }
    val immDom = MutableList<T?>(instances.size) { null }

    for (currentNode in instances.size - 1 downTo 1) {
      // Step 2 of paper.
      // Compute each instance's semi-dominator
      preds[currentNode]?.forEach { predecessor ->
        val evaledPredecessor = eval(ancestors, labels, semis, predecessor)
        if (semis[evaledPredecessor] < semis[currentNode]) {
          semis[currentNode] = semis[evaledPredecessor]
        }
      }
      buckets[semis[currentNode]].add(currentNode)
      ancestors[currentNode] = parents[currentNode]

      // Step 3 of paper.
      // Implicitly define each node's immediate dominator by Corollary 1
      for (i in 0 until buckets[parents[currentNode]].size) {
        val node = buckets[parents[currentNode]][i]
        val nodeEvaled = eval(ancestors, labels, semis, node)
        doms[node] =
          if (semis[nodeEvaled] < semis[node]) nodeEvaled else parents[currentNode]
        immDom[node] = instances[doms[node]]
      }
      buckets[parents[currentNode]].clear() // Bulk remove (slightly different from paper).
    }

    // Step 4 of paper.
    // Explicitly define each node's immediate dominator
    for (currentNode in 1 until instances.size) {
      if (doms[currentNode] != semis[currentNode]) {
        doms[currentNode] = doms[doms[currentNode]]
        immDom[currentNode] = instances[doms[currentNode]]
      }
    }

    return Result(instances, immDom)
  }

  /** Traverse the instances depth-first, marking their order and parents in the DFS-tree  */
  private fun <T> computeIndicesAndParents(
    roots: Set<T>,
    next: (T) -> Sequence<T>
  ): DFSResult<T> {
    val instances = ArrayList<Node<T>?>()
    val nodeStack = Stack<Node<T>>()
    instances.add(null) // auxiliary root at 0
    val newNode = Node.newFactory(next)
    roots.forEach {
      val root = newNode(it).apply { parent = 0; predecessors.add(0) }
      nodeStack.push(root)
    }
    while (!nodeStack.empty()) {
      val node = nodeStack.pop()
      if (node.topoOrder < 0) {
        node.topoOrder = instances.size
        instances.add(node)

        for (succ in node.successors) {
          succ.predecessors.add(node.topoOrder)
          if (succ.topoOrder < 0) {
            succ.parent = node.topoOrder
            nodeStack.push(succ)
          }
        }
      }
    }
    val parentIndices = IntArray(instances.size)
    // Note: this was changed from an array of int arrays which would use only the exactly memory
    // needed but would have required array copies.
    val predIndices = arrayOfNulls<IntList?>(instances.size)
    for (i in 1 until instances.size) { // omit auxiliary root at [0]
      val instance = instances[i]!!
      parentIndices[i] = instance.parent
      predIndices[i] = instance.predecessors
    }
    return DFSResult(instances.map { it?.content }, parentIndices, predIndices)
  }

  data class Result<T>(
    val topoOrder: List<T?>,
    val immediateDominator: List<T?>
  )
}

private data class DFSResult<T>(
  val instances: List<T?>,
  val parents: IntArray, // Predecessors not involved in DFS, but lumped in here for 1 pass. Paper did same.
  val predecessors: Array<IntList?>
)

private fun eval(
  ancestors: IntArray,
  labels: IntArray,
  semis: IntArray,
  node: Int
) =
  when (ancestors[node]) {
    INVALID_ANCESTOR -> node
    else -> compress(ancestors, labels, semis, node)
  }

/**
 *  @return a node's evaluation after compression
 */
private fun compress(
  ancestors: IntArray,
  labels: IntArray,
  semis: IntArray,
  node: Int
): Int {
  // This was using Trove4j's default capacity, 10 entries.
  val compressArray = MutableIntList(10)
  assert(ancestors[node] != INVALID_ANCESTOR)
  var n = node
  while (ancestors[ancestors[n]] != INVALID_ANCESTOR) {
    compressArray.add(n)
    n = ancestors[n]
  }
  for (i in compressArray.size - 1 downTo 0) {
    val toCompress = compressArray[i]
    val ancestor = ancestors[toCompress]
    assert(ancestor != INVALID_ANCESTOR)
    if (semis[labels[ancestor]] < semis[labels[toCompress]]) {
      labels[toCompress] = labels[ancestor]
    }
    ancestors[toCompress] = ancestors[ancestor]
  }
  return labels[node]
}

// 0 would coincide with valid parent. Paper uses 0 because they count from 1.
private val INVALID_ANCESTOR = -1

// Augment the original graph with additional information (e.g. topological order, predecessors'
// orders, etc.)
private class Node<T> private constructor(
  val content: T,
  next: (T) -> Sequence<T>,
  wrap: (T) -> Node<T>
) {
  val successors: List<Node<T>> by lazy { next(content).map(wrap).toList() }
  var topoOrder = -1 // topological order from our particular traversal, also used as id
  var parent = -1

  // This was using Trove4j's default capacity, 10 entries.
  var predecessors = MutableIntList(10)

  companion object {
    fun <T> newFactory(next: (T) -> Sequence<T>): (T) -> Node<T> =
      HashMap<T, Node<T>>().let { cache ->
        fun wrap(content: T): Node<T> =
          cache.getOrPut(content) { Node(content, next, ::wrap) }
        ::wrap
      }
  }
}
