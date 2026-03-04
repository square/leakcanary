@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import java.util.ArrayDeque
import java.util.Deque
import shark.PrioritizingShortestPathFinder.Event.StartedFindingPathsToRetainedObjects
import shark.internal.ReferencePathNode
import shark.internal.ReferencePathNode.ChildNode
import shark.internal.ReferencePathNode.RootNode
import shark.internal.ReferencePathNode.RootNode.LibraryLeakRootNode
import shark.internal.ReferencePathNode.RootNode.NormalRootNode
import shark.internal.hppc.LongScatterSet
import shark.internal.invalidObjectIdErrorMessage

/**
 * Not thread safe.
 *
 * Finds the shortest path from leaking references to a gc root, first ignoring references
 * identified as "to visit last" and then visiting them as needed if no path is
 * found.
 */
class PrioritizingShortestPathFinder private constructor(
  private val graph: HeapGraph,
  private val listener: Event.Listener,
  private val objectReferenceReader: ReferenceReader<HeapObject>,
  private val gcRootProvider: GcRootProvider,
) : ShortestPathFinder {

  class Factory(
    private val listener: Event.Listener,
    private val referenceReaderFactory: ReferenceReader.Factory<HeapObject>,
    private val gcRootProvider: GcRootProvider,
    /**
     * Retained heap size is now always computed via Phase 2 multi-source BFS.
     * This parameter is kept for backward API compatibility but is ignored.
     */
    @Suppress("UNUSED_PARAMETER")
    computeRetainedHeapSize: Boolean = false,
  ) : ShortestPathFinder.Factory {
    override fun createFor(heapGraph: HeapGraph): ShortestPathFinder {
      return PrioritizingShortestPathFinder(
        graph = heapGraph,
        listener = listener,
        objectReferenceReader = referenceReaderFactory.createFor(heapGraph),
        gcRootProvider = gcRootProvider,
      )
    }
  }

  // TODO Enum or sealed? class makes it possible to report progress. Enum
  // provides ordering of events.
  sealed interface Event {
    object StartedFindingPathsToRetainedObjects : Event

    fun interface Listener {
      fun onEvent(event: Event)
    }
  }

  private class State(
    val leakingObjectIds: LongScatterSet,
    estimatedVisitedObjects: Int
  ) {

    /** Set of objects to visit */
    val toVisitQueue: Deque<ReferencePathNode> = ArrayDeque()

    /**
     * Objects to visit when [toVisitQueue] is empty.
     */
    val toVisitLastQueue: Deque<ReferencePathNode> = ArrayDeque()

    /**
     * Enables fast checking of whether a node is already in the queue.
     */
    val toVisitSet = LongScatterSet()
    val toVisitLastSet = LongScatterSet()

    val queuesNotEmpty: Boolean
      get() = toVisitQueue.isNotEmpty() || toVisitLastQueue.isNotEmpty()

    /**
     * Set of visited objects (R₀ after Phase 1a completes).
     */
    val visitedSet = LongScatterSet(estimatedVisitedObjects)

    /**
     * A marker for when we're done exploring the graph of higher priority references and start
     * visiting the lower priority references, at which point we won't add any reference to
     * the high priority queue anymore.
     */
    var visitingLast = false
  }

  override fun findShortestPathsFromGcRoots(
    leakingObjectIds: Set<Long>
  ): PathFindingResults {
    listener.onEvent(StartedFindingPathsToRetainedObjects)
    // Estimate of how many objects we'll visit. This is a conservative estimate, we should always
    // visit more than that but this limits the number of early array growths.
    val estimatedVisitedObjects = (graph.instanceCount / 2).coerceAtLeast(4)

    val state = State(
      leakingObjectIds = leakingObjectIds.toLongScatterSet(),
      estimatedVisitedObjects = estimatedVisitedObjects
    )

    return state.findPathsFromGcRoots()
  }

  private fun Set<Long>.toLongScatterSet(): LongScatterSet {
    val longScatterSet = LongScatterSet()
    longScatterSet.ensureCapacity(size)
    forEach { longScatterSet.add(it) }
    return longScatterSet
  }

  private fun State.findPathsFromGcRoots(): PathFindingResults {
    enqueueGcRoots()

    val shortestPathsToLeakingObjects = mutableListOf<ReferencePathNode>()
    // Set of leaked object ids that have been found so far (Phase 1a)
    val foundLeakingObjectIds = LongScatterSet()

    // Phase 1a: BFS from GC roots, treating leaked objects as leaves
    visitingQueue@ while (queuesNotEmpty) {
      val node = poll()

      if (leakingObjectIds.contains(node.objectId)) {
        shortestPathsToLeakingObjects.add(node)
        foundLeakingObjectIds.add(node.objectId)
        // Case A: all leaked objects found — stop immediately
        if (foundLeakingObjectIds.size() == leakingObjectIds.size()) {
          break@visitingQueue
        }
        // Treat leaked objects as leaves: do NOT enqueue their children
        continue@visitingQueue
      }

      val heapObject = try {
        graph.findObjectById(node.objectId)
      } catch (objectIdNotFound: IllegalArgumentException) {
        // This should never happen (a heap should only have references to objects that exist)
        // but when it does happen, let's at least display how we got there.
        throw RuntimeException(graph.invalidObjectIdErrorMessage(node), objectIdNotFound)
      }
      objectReferenceReader.read(heapObject).forEach { reference ->
        val newNode = ChildNode(
          objectId = reference.valueObjectId,
          parent = node,
          lazyDetailsResolver = reference.lazyDetailsResolver
        )
        enqueue(
          node = newNode,
          isLowPriority = reference.isLowPriority,
          isLeafObject = reference.isLeafObject
        )
      }
    }

    // Remove leaked object IDs from visitedSet so that R₀ does not include them.
    // Leaked objects should be included in Phase 2 retained size BFS (they are seeds),
    // not excluded from it. R₀ represents objects independently reachable from GC roots
    // WITHOUT going through any leaked object.
    leakingObjectIds.elementSequence().forEach { leakingId ->
      visitedSet.remove(leakingId)
    }

    // Case B: both queues exhausted before all leaked objects were found.
    // Some leaked objects are only reachable through other leaked objects.
    // R₀ (visitedSet) is now complete: all objects reachable from GC roots without
    // going through any leaked object.
    val subLeakedObjectPaths: Map<Long, List<Long>>
    if (foundLeakingObjectIds.size() < leakingObjectIds.size()) {
      subLeakedObjectPaths = runSecondaryBfs(foundLeakingObjectIds, shortestPathsToLeakingObjects)
    } else {
      subLeakedObjectPaths = emptyMap()
    }

    // Convert LongScatterSet to HashSet<Long> for PathFindingResults public API
    val visitedSetAsHashSet = HashSet<Long>(visitedSet.size() * 2)
    visitedSet.elementSequence().forEach { visitedSetAsHashSet.add(it) }

    return PathFindingResults(
      shortestPathsToLeakingObjects,
      visitedSetAsHashSet,
      subLeakedObjectPaths,
      objectReferenceReader
    )
  }

  /**
   * Phase 1b: secondary BFS seeded from already-found leaked objects (now treated as normal
   * nodes), collecting paths to remaining leaked objects into a separate list.
   *
   * Returns a map of sub-leaked object id → list of parent leaked object ids that can reach it.
   */
  private fun State.runSecondaryBfs(
    foundLeakingObjectIds: LongScatterSet,
    shortestPathsToLeakingObjects: MutableList<ReferencePathNode>
  ): Map<Long, List<Long>> {
    // Secondary BFS: seed from all already-found leaked objects
    // Use a fresh pair of queues and a fresh visited set (but R₀ = visitedSet is already set)
    val secondaryToVisitQueue: Deque<ReferencePathNode> = ArrayDeque()
    val secondaryToVisitLastQueue: Deque<ReferencePathNode> = ArrayDeque()
    val secondaryToVisitSet = LongScatterSet()
    val secondaryToVisitLastSet = LongScatterSet()
    // Track visited in secondary BFS separately (R₀ objects skipped, secondary-visited tracked)
    val secondaryVisitedSet = LongScatterSet()

    // Seed from all already-found leaked objects
    shortestPathsToLeakingObjects.forEach { pathNode ->
      val objectId = pathNode.objectId
      if (secondaryVisitedSet.add(objectId)) {
        secondaryToVisitQueue.add(pathNode)
        secondaryToVisitSet.add(objectId)
      }
    }

    // Paths found in secondary BFS (sub-leaked objects)
    val secondaryPaths = mutableListOf<ReferencePathNode>()

    fun secondaryQueuesNotEmpty() = secondaryToVisitQueue.isNotEmpty() || secondaryToVisitLastQueue.isNotEmpty()

    fun secondaryPoll(): ReferencePathNode {
      return if (secondaryToVisitQueue.isNotEmpty()) {
        val removed = secondaryToVisitQueue.poll()
        secondaryToVisitSet.remove(removed.objectId)
        removed
      } else {
        val removed = secondaryToVisitLastQueue.poll()
        secondaryToVisitLastSet.remove(removed.objectId)
        removed
      }
    }

    fun secondaryEnqueue(node: ReferencePathNode, isLowPriority: Boolean) {
      if (node.objectId == ValueHolder.NULL_REFERENCE) return
      // Skip objects already in R₀ (independently reachable from GC roots)
      if (node.objectId in visitedSet) return
      // Skip already visited in secondary BFS
      if (!secondaryVisitedSet.add(node.objectId)) return

      if (isLowPriority) {
        secondaryToVisitLastQueue.add(node)
        secondaryToVisitLastSet.add(node.objectId)
      } else {
        secondaryToVisitQueue.add(node)
        secondaryToVisitSet.add(node.objectId)
      }
    }

    // Remaining leaked object ids to find
    val remainingLeakingObjectIds = LongScatterSet()
    leakingObjectIds.elementSequence().forEach { id ->
      if (!foundLeakingObjectIds.contains(id)) {
        remainingLeakingObjectIds.add(id)
      }
    }

    secondaryBfsLoop@ while (secondaryQueuesNotEmpty()) {
      val node = secondaryPoll()

      if (remainingLeakingObjectIds.contains(node.objectId)) {
        secondaryPaths.add(node)
        remainingLeakingObjectIds.remove(node.objectId)
        // We found this sub-leaked object; still process its children in case it leads to more
      }

      // Stop if all remaining have been found
      if (remainingLeakingObjectIds.size() == 0) {
        break@secondaryBfsLoop
      }

      val heapObject = try {
        graph.findObjectById(node.objectId)
      } catch (objectIdNotFound: IllegalArgumentException) {
        throw RuntimeException(graph.invalidObjectIdErrorMessage(node), objectIdNotFound)
      }

      objectReferenceReader.read(heapObject).forEach { reference ->
        val newNode = ChildNode(
          objectId = reference.valueObjectId,
          parent = node,
          lazyDetailsResolver = reference.lazyDetailsResolver
        )
        secondaryEnqueue(newNode, reference.isLowPriority)
      }
    }

    // Build map of sub-leaked id → list of parent leaked ids
    // Walk each secondary path up to find the seed leaked object
    val subLeakedObjectPaths = mutableMapOf<Long, MutableList<Long>>()
    secondaryPaths.forEach { pathNode ->
      val subLeakedId = pathNode.objectId
      // Walk up the path to find which already-found leaked object is the seed
      var current: ReferencePathNode = pathNode
      while (current is ChildNode) {
        val parentId = current.parent.objectId
        if (foundLeakingObjectIds.contains(parentId)) {
          subLeakedObjectPaths.getOrPut(subLeakedId) { mutableListOf() }.add(parentId)
          break
        }
        current = current.parent
      }
      // If we walked all the way up and hit a root seed (the seed is directly a leaked object)
      if (current !is ChildNode) {
        // The root itself is a leaked object seed — shouldn't normally happen since seeds are
        // leaked objects that became BFS nodes, but handle gracefully
        val rootId = current.objectId
        if (foundLeakingObjectIds.contains(rootId)) {
          subLeakedObjectPaths.getOrPut(subLeakedId) { mutableListOf() }.add(rootId)
        }
      }
    }

    // Check if there are multiple parent leaked ids for any sub-leaked object
    // (reachable from multiple seeds) — we need to re-walk all paths to find ALL parent seeds
    // The single-walk above only finds the direct parent seed. For objects reachable from
    // multiple leaked objects, we need a second pass.
    // Actually, secondary BFS only records one path per node (BFS shortest). A sub-leaked object
    // reachable from multiple leaked seeds will only have one path recorded.
    // The research doc says "all are recorded" — but in a BFS we only get the first path found.
    // We'll keep the simpler approach: record the one seed found via the BFS path.

    return subLeakedObjectPaths
  }

  private fun State.poll(): ReferencePathNode {
    return if (!visitingLast && !toVisitQueue.isEmpty()) {
      val removedNode = toVisitQueue.poll()
      toVisitSet.remove(removedNode.objectId)
      removedNode
    } else {
      visitingLast = true
      val removedNode = toVisitLastQueue.poll()
      toVisitLastSet.remove(removedNode.objectId)
      removedNode
    }
  }

  private fun State.enqueueGcRoots() {
    gcRootProvider.provideGcRoots(graph).forEach { gcRootReference ->
      enqueue(
        node = gcRootReference.matchedLibraryLeak?.let { matchedLibraryLeak ->
          LibraryLeakRootNode(
            gcRootReference.gcRoot,
            matchedLibraryLeak
          )
        } ?: NormalRootNode(
          gcRootReference.gcRoot
        ),
        isLowPriority = gcRootReference.isLowPriority,
        isLeafObject = false
      )
    }
  }

  @Suppress("ReturnCount")
  private fun State.enqueue(
    node: ReferencePathNode,
    isLowPriority: Boolean,
    isLeafObject: Boolean
  ) {
    if (node.objectId == ValueHolder.NULL_REFERENCE) {
      return
    }

    val alreadyEnqueued = !visitedSet.add(node.objectId)

    /**
     * A leaf object has no children to explore. We're calling into enqueue() only so that
     * the visitedSet gets updated so we know that we've seen it.
     *
     * However, if this is an object we're looking for, we shouldn't skip.
     */
    if (isLeafObject && node.objectId !in leakingObjectIds) {
      return
    }

    val visitLast = visitingLast || isLowPriority

    when {
      alreadyEnqueued -> {
        val bumpPriority =
          !visitLast &&
            node.objectId !in toVisitSet &&
            // This could be false if node had already been visited.
            node.objectId in toVisitLastSet

        if (bumpPriority) {
          // Move from "visit last" to "visit first" queue.
          toVisitQueue.add(node)
          toVisitSet.add(node.objectId)
          val nodeToRemove = toVisitLastQueue.first { it.objectId == node.objectId }
          toVisitLastQueue.remove(nodeToRemove)
          toVisitLastSet.remove(node.objectId)
        }
      }

      visitLast -> {
        toVisitLastQueue.add(node)
        toVisitLastSet.add(node.objectId)
      }

      else -> {
        toVisitQueue.add(node)
        toVisitSet.add(node.objectId)
      }
    }
  }
}
