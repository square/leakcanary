package org.leakcanary.screens

import androidx.collection.IntList
import androidx.collection.MutableIntList
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.EnumSet
import java.util.Stack
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import leakcanary.AndroidDebugHeapDumper
import org.leakcanary.WhileSubscribedOrRetained
import org.leakcanary.screens.Destination.TreeMapDestination
import org.leakcanary.screens.TreeMapState.Loading
import org.leakcanary.screens.TreeMapState.Success
import org.leakcanary.screens.TreemapLayout.NodeValue
import shark.ActualMatchingReferenceReaderFactory
import shark.AndroidObjectSizeCalculator
import shark.AndroidReferenceMatchers
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.IgnoredReferenceMatcher
import shark.ObjectDominators
import shark.ObjectDominators.OfflineDominatorNode
import shark.ReferenceMatcher
import shark.ValueHolder

sealed interface TreeMapState {
  object Loading : TreeMapState
  class Success(val dominators: Map<Long, OfflineDominatorNode>) : TreeMapState
}

@HiltViewModel
class TreeMapViewModel @Inject constructor(
  navigator: Navigator
) : ViewModel() {

  val state =
    navigator.filterDestination<TreeMapDestination>()
      .flatMapLatest { destination ->
        stateStream(destination.heapDump)
      }.stateIn(
        viewModelScope, started = WhileSubscribedOrRetained, initialValue = Loading
      )

  private fun stateStream(heapDump: File) = flow<TreeMapState> {
    val result = withContext(Dispatchers.IO) {
      heapDump.openHeapGraph().use { heapGraph ->
        val weakAndFinalizerRefs = EnumSet.of(
          AndroidReferenceMatchers.REFERENCES, AndroidReferenceMatchers.FINALIZER_WATCHDOG_DAEMON
        )
        val ignoredRefs =
          ReferenceMatcher.fromListBuilders(weakAndFinalizerRefs).map { matcher ->
            matcher as IgnoredReferenceMatcher
          }

        ObjectDominators().buildOfflineDominatorTree(heapGraph, ignoredRefs)
      }
    }
    emit(Success(result))
  }
}

@Composable fun TreeMapScreen(viewModel: TreeMapViewModel = viewModel()) {
  val stateProp by viewModel.state.collectAsState()

  when (val state = stateProp) {
    is Loading -> {
      Text("Loading...")
    }

    is Success -> {
      val dominators = state.dominators
      val root = ValueHolder.NULL_REFERENCE
      val treemapInput = DominatorNodeMapper(
        dominators = dominators,
        // TODO Ideally depth & min size would be handled dynamically
        // by the layout algo based on available space, so as not to keep rectangles
        // large enough.
        maxDepth = 1,
        minSize = 10000
      ).mapToTreemapInput(root)
      Treemap(treemapInput) { dominators.getValue(it).name }
    }
  }
}

class DominatorNodeMapper(
  private val dominators: Map<Long, OfflineDominatorNode>,
  private val maxDepth: Int,
  private val minSize: Int
) {

  fun mapToTreemapInput(
    objectId: Long,
    depth: Int = 0
  ): NodeValue<Long> {
    val offlineNode = dominators.getValue(objectId)
    val node = offlineNode.node
    val children = if (depth > maxDepth) {
      emptyList()
    } else {
      node.dominatedObjectIds.mapNotNull { dominatedObjectId ->
        val node = dominators.getValue(dominatedObjectId).node
        // Ignoring small nodes.
        if ((node.shallowSize + node.retainedSize) >= minSize) {
          mapToTreemapInput(dominatedObjectId, depth + 1)
        } else {
          null
        }
      }
    }
    val value = if (objectId == ValueHolder.NULL_REFERENCE) {
      // Root is a forest, retained size isn't computed.
      node.dominatedObjectIds.sumOf { dominatedObjectId ->
        val childNode = dominators.getValue(dominatedObjectId).node
        childNode.shallowSize + childNode.retainedSize
      }
    } else {
      node.shallowSize + node.retainedSize
    }
    return NodeValue(
      value = value,
      content = objectId,
      children = children
    )
  }
}

@Composable
@Preview(device = Devices.FOLDABLE)
fun RealHprofHeapTreemapPreview() {
  val heapDumpFile = File("/Users/py/Desktop/memory-20240919T161101.hprof")

  val root = heapDumpFile.openHeapGraph().use { graph ->
    val referenceReader = ActualMatchingReferenceReaderFactory(emptyList()).createFor(graph)

    val traversalRoots = graph.findClassByName("android.app.Activity")?.instances ?: emptySequence()

    val (sortedHeapNodes, immediateDominators) = LinkEvalDominators().computeDominators(traversalRoots.map {
      HeapNode(
        it.objectId
      )
    }.toSet()) { (sourceObjectId) ->
      val sourceObject = graph.findObjectById(sourceObjectId)
      referenceReader.read(sourceObject).mapNotNull { reference ->
        val targetObject = graph.findObjectById(reference.valueObjectId)
        val isView = targetObject is HeapInstance &&
          targetObject instanceOf "android.view.View"
        val isViewArray = targetObject is HeapObjectArray &&
          targetObject.arrayClassName == "android.view.View[]"
        if (isView || isViewArray) {
          HeapNode(targetObject.objectId)
        } else {
          null
        }
      }
    }

    val objectIdsInTopologicalOrder = sortedHeapNodes.map { it?.objectId }

    val objectSizeCalculator = AndroidObjectSizeCalculator(graph)
    val dominatorsByObjectId = objectIdsInTopologicalOrder.mapNotNull { objectIdOrNull ->
      objectIdOrNull?.let { objectId ->
        val name =when(val heapObject = graph.findObjectById(objectId)) {
          is HeapClass -> "class ${heapObject.name}"
          is HeapInstance -> "${heapObject.instanceClassSimpleName}"
          is HeapObjectArray -> "${heapObject.arrayClassSimpleName}"
          is HeapPrimitiveArray -> "${heapObject.primitiveType.name}"
        }
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

    fun DominatorObject.mapToTreeMapNode(): NodeValue<String> {
      val children = dominatedNodes.map { it.mapToTreeMapNode() }
      return NodeValue(retainedSize, name, children)
    }

    val rootDominators = dominatorsByObjectId.values.filter { it.parent == null }

    NodeValue(
      value = rootDominators.sumOf { it.retainedSize },
      content = "root",
      children = rootDominators.map { it.mapToTreeMapNode() }
    )
  }

  Treemap(root) { it }
}

data class HeapNode(val objectId: Long)

class DominatorObject(
  val name: String,
  val shallowSize: Int
) {
  var parent: DominatorObject? = null
  val dominatedNodes = mutableListOf<DominatorObject>()
  var retainedSize = shallowSize
}

@Composable
@Preview
fun OnDeviceHeapTreemapPreview() {
  val filesDir = LocalContext.current.filesDir
  val heapDumpFile = File(filesDir, "heapdump-${System.currentTimeMillis()}.hprof")
  AndroidDebugHeapDumper.dumpHeap(heapDumpFile)
  val dominators = heapDumpFile.openHeapGraph().use { heapGraph ->
    val weakAndFinalizerRefs = EnumSet.of(
      AndroidReferenceMatchers.REFERENCES, AndroidReferenceMatchers.FINALIZER_WATCHDOG_DAEMON
    )
    val ignoredRefs =
      ReferenceMatcher.fromListBuilders(weakAndFinalizerRefs).map { matcher ->
        matcher as IgnoredReferenceMatcher
      }

    ObjectDominators().buildOfflineDominatorTree(heapGraph, ignoredRefs)
  }
  val root = ValueHolder.NULL_REFERENCE
  val treemapInput = DominatorNodeMapper(
    dominators = dominators,
    // TODO Ideally depth & min size would be handled dynamically
    // by the layout algo based on available space, so as not to keep rectangles
    // large enough.
    maxDepth = 1,
    minSize = 10000
  ).mapToTreemapInput(root)
  Treemap(treemapInput) { dominators.getValue(it).name }
}

@Composable
@Preview
fun TreemapPreview() {
  val root = NodeValue(
    25,
    "Root",
    listOf(
      NodeValue(
        10, "A", listOf(
        NodeValue(5, "A1", emptyList()),
        NodeValue(5, "A2", emptyList())
      )
      ),
      NodeValue(5, "B", emptyList()),
      NodeValue(5, "C", emptyList()),
      NodeValue(5, "D", emptyList()),
    )
  )
  Treemap(root, text = { it })
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun <T> Treemap(
  root: NodeValue<T>,
  text: (T) -> String
) {
  // TODO Colors should be a gradient related to depth
  //  Try colors from https://observablehq.com/@d3/nested-treemap
  //  Also colors as related to the node.
  //  d3.scaleSequential([8, 0], d3.interpolateMagma)
  val colors = listOf(
    Color(169, 64, 119),
    Color(206, 88, 98),
    Color(237, 143, 106),
    Color(253, 253, 198),
    Color(253, 253, 198),
    Color(253, 253, 198),
    Color(253, 253, 198),
    Color(253, 253, 198),
    Color(253, 253, 198),
    Color(253, 253, 198),
    Color(253, 253, 198),
    Color(253, 253, 198),
  )
  val textMeasure = rememberTextMeasurer()

  Canvas(modifier = Modifier.fillMaxSize()) {

    val layout = TreemapLayout<T>(
      // TODO This isn't working
      paddingInner = { 0f },
      paddingLeft = { 32f },
      paddingTop = { 64f },
      paddingRight = { 32f },
      paddingBottom = { 32f }

    ).layout(root, size)

    layout.depthFirstTraversal { node ->
      val topLeft = node.topLeft
      val size = node.size
      drawRect(
        colors[node.depth],
        topLeft = node.topLeft + Offset(1f, 1f),
        size = Size(node.size.width, node.size.height)
      )
      val leftX = topLeft.x
      val topY = topLeft.y
      val rightX = topLeft.x + size.width - 1
      val bottomY = topLeft.y + size.height - 1
      drawLine(color = Color.Black, start = topLeft, end = Offset(rightX, topY), strokeWidth = 2f)
      drawLine(color = Color.Black, start = topLeft, end = Offset(leftX, bottomY), strokeWidth = 2f)
      drawLine(color = Color.Black, start = Offset(leftX, bottomY), end = Offset(rightX, bottomY), strokeWidth = 2f)
      drawLine(color = Color.Black, start = Offset(rightX, topY), end = Offset(rightX, bottomY), strokeWidth = 2f)
      // TODO Figure out what's up with negative numbers
      // java.lang.IllegalArgumentException: maxHeight(-1233) must be >= than minHeight(0)
      // if (node.x0 > 0 && node.y0 > 0) {
      drawText(
        textMeasurer = textMeasure,
        text = text(node.content),
        topLeft = node.topLeft + Offset(4f, 4f)
      )
      // }
    }
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
