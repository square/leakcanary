@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import androidx.collection.IntList
import androidx.collection.MutableIntList
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
   * The [HeapGraph] this was built for. Held to turn an `objectIndex` back into an object id, and
   * for its [CancelSignal]: [buildNodes] spends most of its time walking arrays rather than reading
   * the heap dump, so it has to ask about cancellation itself.
   */
  private val graph: HeapGraph,
  /**
   * Vertices are identified by their 1 based DFS preorder number, and each holds the `objectIndex`
   * of its object. Index 0 is unused, index 1 is the virtual root that dominates every GC root,
   * which is no object of the heap dump and therefore [NO_OBJECT_INDEX].
   */
  private val objectIndexByDfsNumber: IntList,
  private val dfsNumberByObjectIndex: IntArray,
  private val dominatorDfsNumberByDfsNumber: IntArray,
) {

  /** Number of objects reachable from the GC roots. */
  val reachableObjectCount: Int
    get() = objectIndexByDfsNumber.size - VIRTUAL_ROOT - 1

  /**
   * The id of the object that immediately dominates [objectId], or [ValueHolder.NULL_REFERENCE]
   * when [objectId] is only dominated by the GC roots as a group.
   *
   * Throws [IllegalArgumentException] if [objectId] isn't reachable from the GC roots.
   */
  fun immediateDominatorOf(objectId: Long): Long {
    val objectIndex = graph.objectIndexOf(objectId)
    val dfsNumber = if (objectIndex == NO_OBJECT_INDEX) {
      NOT_REACHABLE
    } else {
      dfsNumberByObjectIndex[objectIndex]
    }
    require(dfsNumber != NOT_REACHABLE && dfsNumber != VIRTUAL_ROOT) {
      "Object id $objectId is not reachable from the GC roots"
    }
    return objectIdAt(dominatorDfsNumberByDfsNumber[dfsNumber])
  }

  /** The id of the object of the vertex numbered [dfsNumber]. */
  private fun objectIdAt(dfsNumber: Int): Long {
    val objectIndex = objectIndexByDfsNumber[dfsNumber]
    return if (objectIndex == NO_OBJECT_INDEX) {
      ValueHolder.NULL_REFERENCE
    } else {
      graph.findObjectByIndex(objectIndex).objectId
    }
  }

  /**
   * Turns this tree into one [DominatorNode] per reachable object, keyed by object id, plus a
   * [ValueHolder.NULL_REFERENCE] entry for the virtual root of the forest.
   */
  fun buildNodes(objectSizeCalculator: ObjectSizeCalculator): Map<Long, DominatorNode> {
    val cancelSignal = graph.cancelSignal
    val lastDfsNumber = objectIndexByDfsNumber.size - 1
    // Both are Longs: the shallow size of one array can be more than 2 GB on its own, and retained
    // sizes accumulate up the tree, so at the root they add up to the whole reachable heap.
    val shallowSizes = LongArray(lastDfsNumber + 1)
    val retainedSizes = LongArray(lastDfsNumber + 1)
    val retainedCounts = IntArray(lastDfsNumber + 1)

    for (dfsNumber in VIRTUAL_ROOT + 1..lastDfsNumber) {
      val shallowSize = objectSizeCalculator.computeSize(objectIdAt(dfsNumber))
      shallowSizes[dfsNumber] = shallowSize
      retainedSizes[dfsNumber] = shallowSize
      retainedCounts[dfsNumber] = 1
    }

    // The immediate dominator of a vertex always has a lower DFS number than that vertex, so
    // walking the vertices backwards means a vertex is done accumulating before it's added to its
    // own dominator.
    val childrenByDominator = arrayOfNulls<MutableIntList>(lastDfsNumber + 1)
    for (dfsNumber in lastDfsNumber downTo VIRTUAL_ROOT + 1) {
      cancelSignal.throwIfCanceled()
      val dominator = dominatorDfsNumberByDfsNumber[dfsNumber]
      retainedSizes[dominator] += retainedSizes[dfsNumber]
      retainedCounts[dominator] += retainedCounts[dfsNumber]
      val children = childrenByDominator[dominator]
        ?: MutableIntList(1).also { childrenByDominator[dominator] = it }
      // DFS numbers rather than object ids: half the memory, and the sort below reads a retained
      // size by DFS number anyway.
      children += dfsNumber
    }

    val nodes = HashMap<Long, DominatorNode>(lastDfsNumber)
    for (dfsNumber in VIRTUAL_ROOT..lastDfsNumber) {
      cancelSignal.throwIfCanceled()
      val children = childrenByDominator[dfsNumber]
      val dominatedObjectIds = if (children == null) {
        emptyList()
      } else {
        val childDfsNumbers = ArrayList<Int>(children.size)
        children.forEach { childDfsNumbers += it }
        // Largest retained first.
        childDfsNumbers.sortedByDescending { retainedSizes[it] }.map { objectIdAt(it) }
      }
      nodes[objectIdAt(dfsNumber)] = DominatorNode(
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

    /** The `objectIndex` of a vertex that is no object of the heap dump: the virtual root. */
    private const val NO_OBJECT_INDEX = -1

    /**
     * The `objectIndex` of [objectId], dense over `[0, HeapGraph.objectCount[`, or
     * [NO_OBJECT_INDEX] when the heap dump has no object with that id.
     *
     * Keying by that index rather than by object id is what lets every per object array here be an
     * [IntArray] sized once, instead of a hash map that grows and rehashes. The price is a binary
     * search per lookup, the same trade [shark.internal.HeapObjectIdSet] makes.
     */
    private fun HeapGraph.objectIndexOf(objectId: Long): Int {
      return if (this is HprofHeapGraph) {
        objectIndexOrMinusOne(objectId)
      } else {
        // Correct for any other HeapGraph implementation, at the cost of an allocation per lookup.
        findObjectByIdOrNull(objectId)?.objectIndex ?: NO_OBJECT_INDEX
      }
    }

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
        graph = graph,
        objectIndexByDfsNumber = flowGraph.objectIndexByDfsNumber,
        dfsNumberByObjectIndex = flowGraph.dfsNumberByObjectIndex,
        dominatorDfsNumberByDfsNumber = LengauerTarjan(flowGraph, graph.cancelSignal)
          .computeImmediateDominators(),
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
    /**
     * DFS number to `objectIndex`. Index 0 unused, index [VIRTUAL_ROOT] is the virtual root, which
     * is no object of the heap dump. Sized once at the object count, which no DFS can exceed.
     */
    val objectIndexByDfsNumber = MutableIntList(graph.objectCount + 2).apply {
      add(NO_OBJECT_INDEX) // Unused index 0.
      add(NO_OBJECT_INDEX) // The virtual root.
    }

    /** `objectIndex` to DFS number, [NOT_REACHABLE] for an object no GC root reaches. */
    val dfsNumberByObjectIndex = IntArray(graph.objectCount)

    /** DFS number to the DFS number of its DFS tree parent, 0 for the virtual root. */
    val dfsTreeParent = MutableIntList(graph.objectCount + 2).apply {
      add(0)
      add(0)
    }

    /**
     * Predecessors other than the DFS tree parent, one contiguous slice per vertex: the
     * predecessors of the vertex numbered `w` are [predecessorDfsNumber] from
     * `predecessorStart[w]` up to `predecessorStart[w + 1]`.
     *
     * The DFS tree edge into a vertex is left out because [dfsTreeParent] already holds it, and
     * there is one of those per vertex: on a heap dump with 9.26 M reachable objects and 17.3 M
     * references between them, that's 54% of the edges never stored.
     *
     * Both are built by [depthFirstSearch] and read by [LengauerTarjan], which is the only reader
     * either has, and which asks for a vertex's predecessors exactly once.
     */
    lateinit var predecessorStart: IntArray
      private set

    lateinit var predecessorDfsNumber: IntArray
      private set

    /**
     * Every predecessor edge in the order the DFS found it, which is the only order available
     * while neither the vertex count nor the edge count is known yet. Dropped by
     * [buildPredecessorCsr] as soon as the slices above exist.
     */
    private var edgeSourceDfsNumber = MutableIntList()
    private var edgeTargetDfsNumber = MutableIntList()

    val lastDfsNumber: Int
      get() = objectIndexByDfsNumber.size - 1

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
      val cancelSignal = graph.cancelSignal
      val stack = ArrayDeque<Frame>()
      stack.addLast(virtualRootFrame())
      while (stack.isNotEmpty()) {
        // Reading a frame's references reads the heap dump, but descending into a successor already
        // visited doesn't, and an array of a million already visited objects is a million turns
        // around this loop without a single read.
        cancelSignal.throwIfCanceled()
        val frame = stack.last()
        if (frame.cursor == frame.successorObjectIds.size) {
          stack.removeLast()
          continue
        }
        val index = frame.cursor++
        val successorObjectId = frame.successorObjectIds[index]
        val successorObjectIndex = graph.objectIndexOf(successorObjectId)
        require(successorObjectIndex != NO_OBJECT_INDEX) {
          "Heap dump is corrupt: it has a reference to object id $successorObjectId, which is not " +
            "an object of the heap dump"
        }
        val knownDfsNumber = dfsNumberByObjectIndex[successorObjectIndex]
        if (knownDfsNumber != NOT_REACHABLE) {
          addEdge(from = frame.dfsNumber, to = knownDfsNumber)
          continue
        }
        // This edge is the new vertex's DFS tree edge, which [dfsTreeParent] already holds, so it
        // isn't added to the predecessor lists. See [LengauerTarjan.computeImmediateDominators].
        val dfsNumber = newVertex(successorObjectIndex, parentDfsNumber = frame.dfsNumber)
        if (!frame.successorIsLeafObject[index]) {
          stack.addLast(frame(dfsNumber, successorObjectIndex))
        }
      }
      buildPredecessorCsr()
    }

    /**
     * Counting sort of the edges the DFS found into one slice of predecessors per vertex, which
     * then replaces them: an edge in a slice is 4 bytes where an edge in the discovery order lists
     * is 8, and a [MutableIntList] holds up to half again as much as its size, so this hands
     * [LengauerTarjan] about a third of what it would otherwise be allocating on top of.
     */
    private fun buildPredecessorCsr() {
      val edgeCount = edgeTargetDfsNumber.size
      // One more than the last DFS number, so that the last vertex has a slice end to read.
      val start = IntArray(lastDfsNumber + 2)
      for (edge in 0 until edgeCount) {
        start[edgeTargetDfsNumber[edge]]++
      }
      // Now the end of each vertex's slice.
      for (dfsNumber in 1..lastDfsNumber + 1) {
        start[dfsNumber] += start[dfsNumber - 1]
      }
      val predecessors = IntArray(edgeCount)
      // Filling each slice from its end back down turns every end into the start of that slice,
      // which is what leaves `start` holding starts by the time this returns.
      for (edge in edgeCount - 1 downTo 0) {
        val target = edgeTargetDfsNumber[edge]
        start[target]--
        predecessors[start[target]] = edgeSourceDfsNumber[edge]
      }
      predecessorStart = start
      predecessorDfsNumber = predecessors
      edgeSourceDfsNumber = MutableIntList(0)
      edgeTargetDfsNumber = MutableIntList(0)
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
      objectIndex: Int
    ): Frame {
      val successorObjectIds = MutableLongList()
      val successorIsLeafObject = ArrayList<Boolean>()
      // By index rather than by id: the id was resolved to an index to get here, and looking the
      // object up by index is the half of findObjectById that isn't a binary search.
      referenceReader.read(graph.findObjectByIndex(objectIndex)).forEach { reference ->
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
      objectIndex: Int,
      parentDfsNumber: Int
    ): Int {
      objectIndexByDfsNumber += objectIndex
      val dfsNumber = lastDfsNumber
      dfsNumberByObjectIndex[objectIndex] = dfsNumber
      dfsTreeParent.add(parentDfsNumber)
      return dfsNumber
    }

    private fun addEdge(
      from: Int,
      to: Int
    ) {
      edgeSourceDfsNumber += from
      edgeTargetDfsNumber += to
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
  private class LengauerTarjan(
    private val graph: FlowGraph,
    private val cancelSignal: CancelSignal
  ) {

    private val lastDfsNumber = graph.lastDfsNumber

    private val predecessorStart = graph.predecessorStart

    private val predecessorDfsNumber = graph.predecessorDfsNumber

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
      // Nothing below reads the heap dump: it's all array work over a graph that's already been
      // walked, and on a large heap dump it runs for seconds. So both loops ask as they go.
      for (w in lastDfsNumber downTo VIRTUAL_ROOT + 1) {
        cancelSignal.throwIfCanceled()
        val parent = graph.dfsTreeParent[w]
        // The DFS tree edge into w isn't one of w's stored predecessors, and what it contributes
        // to semi[w] is exactly `parent`: this loop descends, so `parent` is still unlinked and
        // still labelled with itself, which makes eval(parent) parent, and its own semidominator
        // hasn't been computed yet so semi[parent] is still its DFS number.
        if (parent < semi[w]) {
          semi[w] = parent
        }
        for (edge in predecessorStart[w] until predecessorStart[w + 1]) {
          val candidate = semi[eval(predecessorDfsNumber[edge])]
          if (candidate < semi[w]) {
            semi[w] = candidate
          }
        }
        // Defer w until its semidominator's own dominator is known.
        nextInBucket[w] = bucketHead[semi[w]]
        bucketHead[semi[w]] = w
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
        cancelSignal.throwIfCanceled()
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
