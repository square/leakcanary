package shark

import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.GcRoot.JniGlobal
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.Companion.NULL_REFERENCE

class HeapDominatorTreeTest {

  @Test fun `a diamond is dominated by its top`() {
    // root -> a -> b -> d
    //          `-> c -^
    val heapDump = graphHeapDump(
      // Node 0 is the only GC root, and each node's successors are node indexes.
      successorsByNode = listOf(
        listOf(1, 2), // a -> b, c
        listOf(3), // b -> d
        listOf(3), // c -> d
        emptyList() // d
      ),
      rootNodes = listOf(0)
    )

    heapDump.openHeapGraph().use { graph ->
      val tree = graph.buildExactDominatorTree()
      val nodeIds = graph.nodeIdsInWriteOrder()

      assertThat(tree.immediateDominatorOf(nodeIds[0])).isEqualTo(NULL_REFERENCE)
      assertThat(tree.immediateDominatorOf(nodeIds[1])).isEqualTo(nodeIds[0])
      assertThat(tree.immediateDominatorOf(nodeIds[2])).isEqualTo(nodeIds[0])
      // d is reachable through b and through c, so neither dominates it.
      assertThat(tree.immediateDominatorOf(nodeIds[3])).isEqualTo(nodeIds[0])
    }
  }

  @Test fun `an object reachable from two gc roots is dominated by the roots as a group`() {
    val heapDump = graphHeapDump(
      successorsByNode = listOf(
        listOf(2),
        listOf(2),
        emptyList()
      ),
      rootNodes = listOf(0, 1)
    )

    heapDump.openHeapGraph().use { graph ->
      val tree = graph.buildExactDominatorTree()
      val nodeIds = graph.nodeIdsInWriteOrder()

      assertThat(tree.immediateDominatorOf(nodeIds[2])).isEqualTo(NULL_REFERENCE)
    }
  }

  @Test fun `matches a brute force dominator computation on random graphs`() {
    val random = Random(42)

    repeat(GRAPH_COUNT) { graphIndex ->
      val nodeCount = 1 + random.nextInt(MAX_NODE_COUNT)
      val successorsByNode = List(nodeCount) {
        List(random.nextInt(MAX_OUT_DEGREE + 1)) { random.nextInt(nodeCount) }
      }
      val rootNodes = List(1 + random.nextInt(MAX_ROOT_COUNT)) { random.nextInt(nodeCount) }

      graphHeapDump(successorsByNode, rootNodes).openHeapGraph().use { graph ->
        val tree = graph.buildExactDominatorTree()
        // Read the graph back rather than trusting successorsByNode, so that the expectation is
        // computed from what the reference reader actually sees.
        val successorsByObjectId = graph.readReachableSuccessors()
        val expected = bruteForceImmediateDominators(successorsByObjectId)

        assertThat(tree.reachableObjectCount)
          .describedAs("graph $graphIndex: $successorsByObjectId")
          .isEqualTo(expected.size)
        expected.forEach { (objectId, expectedDominator) ->
          assertThat(tree.immediateDominatorOf(objectId))
            .describedAs("graph $graphIndex: dominator of $objectId in $successorsByObjectId")
            .isEqualTo(expectedDominator)
        }
      }
    }
  }

  @Test fun `retained sizes past Int MAX_VALUE are not truncated`() {
    // root -> a -> b
    val heapDump = graphHeapDump(
      successorsByNode = listOf(
        listOf(1),
        listOf(2),
        emptyList()
      ),
      rootNodes = listOf(0)
    )

    heapDump.openHeapGraph().use { graph ->
      val tree = graph.buildExactDominatorTree()
      // Every object weighs 1 GB, so the virtual root retains a gigabyte per reachable object,
      // which is past Int.MAX_VALUE for any graph of 2 objects or more.
      val oneGigabyte = 1024 * 1024 * 1024
      val nodes = tree.buildNodes { oneGigabyte }

      val wholeHeapSize = tree.reachableObjectCount.toLong() * oneGigabyte
      assertThat(wholeHeapSize).isGreaterThan(Int.MAX_VALUE.toLong())
      assertThat(nodes.getValue(NULL_REFERENCE).retainedSize).isEqualTo(wholeHeapSize)
      // A leaf retains only itself, so its size is the one that doesn't accumulate.
      assertThat(nodes.getValue(graph.nodeIdsInWriteOrder().last()).retainedSize)
        .isEqualTo(oneGigabyte.toLong())
    }
  }

  private fun HeapGraph.buildExactDominatorTree() = HeapDominatorTree.buildFor(
    graph = this,
    referenceReader = ActualMatchingReferenceReaderFactory(emptyList()).createFor(this),
    gcRootProvider = MatchingGcRootProvider(emptyList())
  )

  /**
   * Object id to the ids of the objects it references, for every object reachable from the GC
   * roots, with [NULL_REFERENCE] standing for the virtual root that references every GC root.
   */
  private fun HeapGraph.readReachableSuccessors(): Map<Long, List<Long>> {
    val referenceReader = ActualMatchingReferenceReaderFactory(emptyList()).createFor(this)
    val successors = LinkedHashMap<Long, List<Long>>()
    val rootSuccessors = mutableListOf<Long>()
    successors[NULL_REFERENCE] = rootSuccessors
    val toVisit = ArrayDeque<Long>()

    MatchingGcRootProvider(emptyList()).provideGcRoots(this).forEach { gcRootReference ->
      val objectId = gcRootReference.gcRoot.id
      if (objectId != NULL_REFERENCE) {
        rootSuccessors += objectId
        toVisit += objectId
      }
    }

    while (toVisit.isNotEmpty()) {
      val objectId = toVisit.removeFirst()
      if (objectId in successors) {
        continue
      }
      val objectSuccessors = mutableListOf<Long>()
      successors[objectId] = objectSuccessors
      referenceReader.read(findObjectById(objectId)).forEach { reference ->
        // The graphs built by graphHeapDump() are object arrays only, so nothing flattens its
        // referent's references into itself. If that ever changes, this walk and the one in
        // HeapDominatorTree would need to agree on which objects are expanded.
        check(!reference.isLeafObject) {
          "Unexpected leaf object reference from $objectId"
        }
        if (reference.valueObjectId != NULL_REFERENCE) {
          objectSuccessors += reference.valueObjectId
          toVisit += reference.valueObjectId
        }
      }
    }
    return successors
  }

  /**
   * The immediate dominator of every object reachable from [NULL_REFERENCE], computed the slow but
   * obvious way: `u` dominates `v` when removing `u` makes `v` unreachable, and the immediate
   * dominator of `v` is the strict dominator of `v` that has the most dominators of its own.
   */
  private fun bruteForceImmediateDominators(
    successors: Map<Long, List<Long>>
  ): Map<Long, Long> {
    val reachable = reachableExcluding(successors, excluded = NULL_REFERENCE)
    val dominatorsByObjectId = reachable.associateWith { objectId ->
      val dominators = mutableSetOf(objectId)
      reachable.forEach { candidate ->
        if (candidate != objectId &&
          objectId !in reachableExcluding(successors, excluded = candidate)
        ) {
          dominators += candidate
        }
      }
      dominators
    }
    return dominatorsByObjectId.mapValues { (objectId, dominators) ->
      (dominators - objectId).maxByOrNull { dominatorsByObjectId.getValue(it).size }
        ?: NULL_REFERENCE
    }
  }

  /** Objects reachable from [NULL_REFERENCE] without going through [excluded]. */
  private fun reachableExcluding(
    successors: Map<Long, List<Long>>,
    excluded: Long
  ): Set<Long> {
    val reached = mutableSetOf<Long>()
    val toVisit = ArrayDeque(successors.getValue(NULL_REFERENCE).filter { it != excluded })
    while (toVisit.isNotEmpty()) {
      val objectId = toVisit.removeFirst()
      if (!reached.add(objectId)) {
        continue
      }
      successors.getValue(objectId).forEach { successor ->
        if (successor != excluded && successor !in reached) {
          toVisit += successor
        }
      }
    }
    return reached
  }

  /**
   * A heap dump made of one `java.lang.Object[]` per node, where node N's array holds the ids of
   * the arrays of `successorsByNode[N]`. Cycles and self references are fine.
   */
  private fun graphHeapDump(
    successorsByNode: List<List<Int>>,
    rootNodes: List<Int>
  ) = dump {
    val arrayClassId = arrayClass("java.lang.Object")
    // Object ids are assigned sequentially as records are written, so writing one array up front
    // tells us the ids the next ones will get, which is what lets nodes reference each other
    // before they're written. Checked below so this can't silently build the wrong graph.
    val firstNodeId = objectArray(arrayClassId, longArrayOf()) + 1
    val nodeIds = LongArray(successorsByNode.size) { firstNodeId + it }

    successorsByNode.forEachIndexed { node, successors ->
      val writtenId =
        objectArray(arrayClassId, LongArray(successors.size) { nodeIds[successors[it]] })
      check(writtenId == nodeIds[node]) {
        "Expected node $node to be written with id ${nodeIds[node]}, was $writtenId"
      }
    }
    rootNodes.forEach { node ->
      gcRoot(JniGlobal(id = nodeIds[node], jniGlobalRefId = 0))
    }
  }

  /** Node ids as assigned by [graphHeapDump], in node order. */
  private fun HeapGraph.nodeIdsInWriteOrder(): List<Long> {
    // The first object array written by graphHeapDump() is the empty sacrificial one.
    return objects.filter { it is HeapObject.HeapObjectArray }
      .map { it.objectId }
      .sorted()
      .drop(1)
      .toList()
  }

  companion object {
    private const val GRAPH_COUNT = 300
    private const val MAX_NODE_COUNT = 24
    private const val MAX_OUT_DEGREE = 4
    private const val MAX_ROOT_COUNT = 3
  }
}
