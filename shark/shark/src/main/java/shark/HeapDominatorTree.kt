@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import androidx.collection.LongIntMap
import androidx.collection.LongList
import androidx.collection.MutableIntList
import androidx.collection.MutableLongIntMap
import androidx.collection.MutableLongList
import shark.ObjectDominators.DominatorNode

/**
 * The dominator tree of every object reachable from the GC roots of a [HeapGraph]: object A
 * dominates object B when every path from a GC root to B goes through A.
 *
 * This is computed with Lengauer-Tarjan, which needs the whole graph up front: the tree holds one
 * entry per reachable object plus one per reference between reachable objects, so it's meant for
 * tools running on a workstation, not for the on device analysis.
 */
class HeapDominatorTree private constructor(
  /**
   * Vertices are identified by their 1 based DFS preorder number. Index 0 is unused, index 1 is
   * the virtual root that dominates every GC root, and its object id is
   * [ValueHolder.NULL_REFERENCE].
   */
  private val objectIdByDfsNumber: LongList,
  private val dfsNumberByObjectId: LongIntMap,
  private val dominatorDfsNumberByDfsNumber: IntArray,
) {

  /** Number of objects reachable from the GC roots. */
  val reachableObjectCount: Int
    get() = objectIdByDfsNumber.size - VIRTUAL_ROOT - 1

  /**
   * The id of the object that immediately dominates [objectId], or [ValueHolder.NULL_REFERENCE]
   * when [objectId] is only dominated by the GC roots as a group.
   *
   * Throws [IllegalArgumentException] if [objectId] isn't reachable from the GC roots.
   */
  fun immediateDominatorOf(objectId: Long): Long {
    val dfsNumber = dfsNumberByObjectId.getOrDefault(objectId, NOT_REACHABLE)
    require(dfsNumber != NOT_REACHABLE && dfsNumber != VIRTUAL_ROOT) {
      "Object id $objectId is not reachable from the GC roots"
    }
    return objectIdByDfsNumber[dominatorDfsNumberByDfsNumber[dfsNumber]]
  }

  /**
   * Turns this tree into one [DominatorNode] per reachable object, keyed by object id, plus a
   * [ValueHolder.NULL_REFERENCE] entry for the virtual root of the forest.
   */
  fun buildNodes(objectSizeCalculator: ObjectSizeCalculator): Map<Long, DominatorNode> {
    val lastDfsNumber = objectIdByDfsNumber.size - 1
    val shallowSizes = IntArray(lastDfsNumber + 1)
    // Retained sizes accumulate up the tree, so unlike the shallow sizes they add up to the size of
    // the whole reachable heap at the root and overflow an Int past 2 GB.
    val retainedSizes = LongArray(lastDfsNumber + 1)
    val retainedCounts = IntArray(lastDfsNumber + 1)

    for (dfsNumber in VIRTUAL_ROOT + 1..lastDfsNumber) {
      val shallowSize = objectSizeCalculator.computeSize(objectIdByDfsNumber[dfsNumber])
      shallowSizes[dfsNumber] = shallowSize
      retainedSizes[dfsNumber] = shallowSize.toLong()
      retainedCounts[dfsNumber] = 1
    }

    // The immediate dominator of a vertex always has a lower DFS number than that vertex, so
    // walking the vertices backwards means a vertex is done accumulating before it's added to its
    // own dominator.
    val childrenByDominator = arrayOfNulls<MutableLongList>(lastDfsNumber + 1)
    for (dfsNumber in lastDfsNumber downTo VIRTUAL_ROOT + 1) {
      val dominator = dominatorDfsNumberByDfsNumber[dfsNumber]
      retainedSizes[dominator] += retainedSizes[dfsNumber]
      retainedCounts[dominator] += retainedCounts[dfsNumber]
      val children = childrenByDominator[dominator]
        ?: MutableLongList(1).also { childrenByDominator[dominator] = it }
      children += objectIdByDfsNumber[dfsNumber]
    }

    val nodes = HashMap<Long, DominatorNode>(lastDfsNumber)
    for (dfsNumber in VIRTUAL_ROOT..lastDfsNumber) {
      val children = childrenByDominator[dfsNumber]
      val dominatedObjectIds = if (children == null) {
        emptyList()
      } else {
        val ids = ArrayList<Long>(children.size)
        children.forEach { ids += it }
        // Largest retained first.
        ids.sortedByDescending { retainedSizes[dfsNumberByObjectId[it]] }
      }
      nodes[objectIdByDfsNumber[dfsNumber]] = DominatorNode(
        shallowSize = shallowSizes[dfsNumber],
        retainedSize = retainedSizes[dfsNumber],
        retainedCount = retainedCounts[dfsNumber],
        dominatedObjectIds = dominatedObjectIds
      )
    }
    return nodes
  }

  companion object {
    /** DFS number of the virtual root, which dominates all the GC roots. */
    private const val VIRTUAL_ROOT = 1

    private const val NOT_REACHABLE = 0

    /**
     * Builds the exact dominator tree of the objects of [graph] that are reachable from its GC
     * roots.
     *
     * A virtual root that dominates every GC root is added first, so that the result is a tree
     * rather than a forest.
     */
    fun buildFor(
      graph: HeapGraph,
      referenceReader: ReferenceReader<HeapObject>,
      gcRootProvider: GcRootProvider,
    ): HeapDominatorTree {
      val flowGraph = FlowGraph(graph, referenceReader, gcRootProvider)
      flowGraph.depthFirstSearch()
      return HeapDominatorTree(
        objectIdByDfsNumber = flowGraph.objectIdByDfsNumber,
        dfsNumberByObjectId = flowGraph.dfsNumberByObjectId,
        dominatorDfsNumberByDfsNumber = LengauerTarjan(flowGraph).computeImmediateDominators(),
      )
    }
  }

  /**
   * The heap graph turned into the shape Lengauer-Tarjan needs: vertices numbered by DFS preorder
   * from a virtual root, each with its DFS tree parent and its predecessors.
   */
  private class FlowGraph(
    private val graph: HeapGraph,
    private val referenceReader: ReferenceReader<HeapObject>,
    private val gcRootProvider: GcRootProvider,
  ) {
    /** DFS number to object id. Index 0 unused, index [VIRTUAL_ROOT] is the virtual root. */
    val objectIdByDfsNumber = MutableLongList(graph.objectCount + 2).apply {
      add(ValueHolder.NULL_REFERENCE) // Unused index 0.
      add(ValueHolder.NULL_REFERENCE) // The virtual root.
    }

    val dfsNumberByObjectId = MutableLongIntMap(graph.objectCount).apply {
      put(ValueHolder.NULL_REFERENCE, VIRTUAL_ROOT)
    }

    /** DFS number to the DFS number of its DFS tree parent, 0 for the virtual root. */
    val dfsTreeParent = MutableIntList(graph.objectCount + 2).apply {
      add(0)
      add(0)
    }

    /**
     * Predecessors, as singly linked lists of edges: [predecessorHead] maps a DFS number to the
     * index of its first edge in [predecessorDfsNumber] / [nextPredecessorEdge], and 0 means no
     * more edges. Edge index 0 is therefore unused.
     */
    val predecessorHead = MutableIntList(graph.objectCount + 2).apply {
      add(0)
      add(0)
    }
    val predecessorDfsNumber = MutableIntList().apply { add(0) }
    val nextPredecessorEdge = MutableIntList().apply { add(0) }

    val lastDfsNumber: Int
      get() = objectIdByDfsNumber.size - 1

    /**
     * A vertex whose references have been read and that still has successors to descend into.
     * Frames only exist for the vertices on the current DFS path.
     */
    private class Frame(
      val dfsNumber: Int,
      val successorObjectIds: LongArray,
      /**
       * Whether the reference to the successor at the same index came from a reader that already
       * surfaced the successor's own references, in which case we must not read them again.
       */
      val successorIsLeafObject: BooleanArray,
    ) {
      var cursor = 0
    }

    fun depthFirstSearch() {
      val stack = ArrayDeque<Frame>()
      stack.addLast(virtualRootFrame())
      while (stack.isNotEmpty()) {
        val frame = stack.last()
        if (frame.cursor == frame.successorObjectIds.size) {
          stack.removeLast()
          continue
        }
        val index = frame.cursor++
        val successorObjectId = frame.successorObjectIds[index]
        val knownDfsNumber = dfsNumberByObjectId.getOrDefault(successorObjectId, NOT_REACHABLE)
        if (knownDfsNumber != NOT_REACHABLE) {
          addEdge(from = frame.dfsNumber, to = knownDfsNumber)
          continue
        }
        val dfsNumber = newVertex(successorObjectId, parentDfsNumber = frame.dfsNumber)
        addEdge(from = frame.dfsNumber, to = dfsNumber)
        if (!frame.successorIsLeafObject[index]) {
          stack.addLast(frame(dfsNumber, successorObjectId))
        }
      }
    }

    private fun virtualRootFrame(): Frame {
      val gcRootObjectIds = MutableLongList()
      gcRootProvider.provideGcRoots(graph).forEach { gcRootReference ->
        val objectId = gcRootReference.gcRoot.id
        if (objectId != ValueHolder.NULL_REFERENCE) {
          gcRootObjectIds += objectId
        }
      }
      val successorObjectIds = LongArray(gcRootObjectIds.size) { gcRootObjectIds[it] }
      return Frame(VIRTUAL_ROOT, successorObjectIds, BooleanArray(successorObjectIds.size))
    }

    private fun frame(
      dfsNumber: Int,
      objectId: Long
    ): Frame {
      val successorObjectIds = MutableLongList()
      val successorIsLeafObject = ArrayList<Boolean>()
      referenceReader.read(graph.findObjectById(objectId)).forEach { reference ->
        successorObjectIds += reference.valueObjectId
        successorIsLeafObject += reference.isLeafObject
      }
      return Frame(
        dfsNumber = dfsNumber,
        successorObjectIds = LongArray(successorObjectIds.size) { successorObjectIds[it] },
        successorIsLeafObject = BooleanArray(successorIsLeafObject.size) {
          successorIsLeafObject[it]
        }
      )
    }

    private fun newVertex(
      objectId: Long,
      parentDfsNumber: Int
    ): Int {
      objectIdByDfsNumber += objectId
      val dfsNumber = lastDfsNumber
      dfsNumberByObjectId.put(objectId, dfsNumber)
      dfsTreeParent.add(parentDfsNumber)
      predecessorHead.add(0)
      return dfsNumber
    }

    private fun addEdge(
      from: Int,
      to: Int
    ) {
      predecessorDfsNumber += from
      nextPredecessorEdge += predecessorHead[to]
      predecessorHead[to] = predecessorDfsNumber.size - 1
    }
  }

  /**
   * Lengauer-Tarjan with the simple link-eval, ie path compression without union by rank, which
   * runs in O(edges * log vertices).
   *
   * See "A Fast Algorithm for Finding Dominators in a Flowgraph", Lengauer & Tarjan, 1979. Vertices
   * are identified by their DFS number throughout, which is why `semi` holds DFS numbers and can be
   * compared directly.
   */
  private class LengauerTarjan(private val graph: FlowGraph) {

    private val lastDfsNumber = graph.lastDfsNumber

    /** Semidominator of each vertex, initially the vertex itself. */
    private val semi = IntArray(lastDfsNumber + 1) { it }

    /** Vertex with the minimum semidominator on the path to the forest root of each vertex. */
    private val label = IntArray(lastDfsNumber + 1) { it }

    /** Parent in the link-eval forest, 0 when the vertex is a forest root. */
    private val ancestor = IntArray(lastDfsNumber + 1)

    private val immediateDominator = IntArray(lastDfsNumber + 1)

    /** Vertices whose semidominator is a given vertex, as singly linked lists. */
    private val bucketHead = IntArray(lastDfsNumber + 1)
    private val nextInBucket = IntArray(lastDfsNumber + 1)

    private val compressStack = IntArray(lastDfsNumber + 1)

    fun computeImmediateDominators(): IntArray {
      for (w in lastDfsNumber downTo VIRTUAL_ROOT + 1) {
        var edge = graph.predecessorHead[w]
        while (edge != 0) {
          val candidate = semi[eval(graph.predecessorDfsNumber[edge])]
          if (candidate < semi[w]) {
            semi[w] = candidate
          }
          edge = graph.nextPredecessorEdge[edge]
        }
        // Defer w until its semidominator's own dominator is known.
        nextInBucket[w] = bucketHead[semi[w]]
        bucketHead[semi[w]] = w
        val parent = graph.dfsTreeParent[w]
        ancestor[w] = parent

        var v = bucketHead[parent]
        bucketHead[parent] = 0
        while (v != 0) {
          val u = eval(v)
          immediateDominator[v] = if (semi[u] < semi[v]) u else parent
          v = nextInBucket[v]
        }
      }
      // Vertices whose immediate dominator was set to their semidominator's dominator need a
      // second pass, in DFS order so that the dominator they point at is already final.
      for (w in VIRTUAL_ROOT + 1..lastDfsNumber) {
        if (immediateDominator[w] != semi[w]) {
          immediateDominator[w] = immediateDominator[immediateDominator[w]]
        }
      }
      immediateDominator[VIRTUAL_ROOT] = 0
      return immediateDominator
    }

    private fun eval(v: Int): Int {
      if (ancestor[v] == 0) {
        return label[v]
      }
      compress(v)
      return label[v]
    }

    /** Flattens the link-eval forest path above [start], carrying the minimum label down. */
    private fun compress(start: Int) {
      var stackSize = 0
      var v = start
      while (ancestor[ancestor[v]] != 0) {
        compressStack[stackSize++] = v
        v = ancestor[v]
      }
      while (stackSize > 0) {
        v = compressStack[--stackSize]
        val vAncestor = ancestor[v]
        if (semi[label[vAncestor]] < semi[label[v]]) {
          label[v] = label[vAncestor]
        }
        ancestor[v] = ancestor[vAncestor]
      }
    }
  }
}
