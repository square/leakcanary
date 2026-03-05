@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import java.util.ArrayDeque
import java.util.Deque
import shark.DominatorTree.ObjectSizeCalculator
import shark.PrioritizingShortestPathFinder.Event.StartedFindingPathsToRetainedObjects
import shark.internal.ReferencePathNode
import shark.internal.ReferencePathNode.ChildNode
import shark.internal.ReferencePathNode.RootNode.LibraryLeakRootNode
import shark.internal.ReferencePathNode.RootNode.NormalRootNode
import shark.internal.hppc.LongScatterSet
import shark.internal.invalidObjectIdErrorMessage
import shark.internal.packedWith
import shark.internal.unpackAsFirstInt
import shark.internal.unpackAsSecondInt
import androidx.collection.MutableLongLongMap

/**
 * Not thread safe.
 *
 * Finds the shortest path from each leaking object to a GC root, using a prioritized BFS.
 * High-priority references are visited first; low-priority references (e.g. library leak
 * matchers) are deferred to a second queue and only visited if needed.
 *
 * ## Algorithm overview
 *
 * ### Phase 1 — GC root BFS (leaked objects treated as leaves)
 *
 * BFS from GC roots where **leaked objects are treated as leaf nodes**: when a leaked object
 * is reached its path is recorded, but its outgoing references are never enqueued. This means
 * the BFS only visits objects that are reachable from GC roots *without* going through any
 * leaked object.
 *
 * The set of all objects visited during this traversal is called **R₀** (stored in
 * [PathFindingResults.visitedSet]). R₀ represents objects that are independently reachable
 * from GC roots — they would survive even if all leaked objects were removed.
 *
 * Stopping conditions depend on [Factory.computeRetainedHeapSize]:
 *
 * - If all N leaked objects are found before both queues are exhausted:
 *   - `computeRetainedHeapSize=false` → stop immediately (R₀ need not be complete)
 *   - `computeRetainedHeapSize=true` → continue draining both queues to complete R₀
 * - If both queues exhaust before all N leaked objects are found: R₀ is now complete, and
 *   [computeRetainedSizes] will handle finding the remaining leaked objects.
 *
 * ### Phase 2 — [computeRetainedSizes]
 *
 * A secondary traversal seeded from the found leaked object ids (processed one at a time).
 * For each leaked object id:
 * - BFS from it, updating visitedSet (R₀ keeps growing)
 * - Any unvisited object encountered has its shallow size attributed to this leaked object id
 * - Any leaked object id encountered (not yet processed) is recorded as a sub-leaked object
 *   of this parent
 * If `computeRetainedHeapSize=false` and only looking for remaining leaked objects, stop as
 * soon as all remaining leaked object ids are found.
 */
class PrioritizingShortestPathFinder private constructor(
  private val graph: HeapGraph,
  private val listener: Event.Listener,
  private val objectReferenceReader: ReferenceReader<HeapObject>,
  private val gcRootProvider: GcRootProvider,
  private val computeRetainedHeapSize: Boolean,
) : ShortestPathFinder {

  class Factory(
    private val listener: Event.Listener,
    private val referenceReaderFactory: ReferenceReader.Factory<HeapObject>,
    private val gcRootProvider: GcRootProvider,
    /**
     * Whether to compute retained heap size for each leaked object id.
     * When false, the traversal stops as soon as all leaked objects are found.
     * When true, the full graph is traversed to compute R₀ and then retained sizes are
     * computed via [computeRetainedSizes].
     */
    val computeRetainedHeapSize: Boolean = false,
  ) : ShortestPathFinder.Factory {
    override fun createFor(heapGraph: HeapGraph): ShortestPathFinder {
      return PrioritizingShortestPathFinder(
        graph = heapGraph,
        listener = listener,
        objectReferenceReader = referenceReaderFactory.createFor(heapGraph),
        gcRootProvider = gcRootProvider,
        computeRetainedHeapSize = computeRetainedHeapSize,
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
     * Set of visited objects (R₀ after Phase 1 completes).
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

  /**
   * Computes retained sizes for the leaked object ids found in [results].
   *
   * This is Phase 2: a sequential BFS seeded from each found leaked object id (one at a time).
   * For each leaked object id:
   * - BFS from it, updating [PathFindingResults.visitedSet] (R₀ keeps growing)
   * - Any newly visited object has its shallow size attributed to this leaked object id
   * - Any leaked object id encountered that has not yet been processed is recorded as a
   *   sub-leaked object of this parent
   *
   * The [subLeakedObjectPaths] in [results] is populated by this method (replacing any
   * previously computed value via a new [PathFindingResults] returned by this method).
   *
   * If [computeRetainedHeapSize] is false on this finder, retained sizes will all be zero and
   * this method is only used to find remaining (sub-) leaked objects. The BFS stops as soon as
   * all leaked object ids have been found.
   *
   * @return a [MutableLongLongMap] where keys are leaked object ids and values are packed
   * (retainedBytes, retainedCount) pairs. Only populated if [computeRetainedHeapSize] is true.
   * Also returns an updated [PathFindingResults] with [PathFindingResults.subLeakedObjectPaths]
   * populated.
   */
  fun computeRetainedSizes(
    results: PathFindingResults,
    objectSizeCalculator: ObjectSizeCalculator
  ): Pair<MutableLongLongMap, PathFindingResults> {
    // visitedSet is R₀: objects independently reachable from GC roots.
    val visitedSet = results.visitedSet

    // All leaked object ids we need to account for
    val allLeakingObjectIds = results.pathsToLeakingObjects.toLongScatterSet()

    // Retained sizes map: leaked object id → packed (retainedBytes, retainedCount)
    val retainedSizes = MutableLongLongMap(allLeakingObjectIds.size())
    allLeakingObjectIds.elementSequence().forEach { id ->
      retainedSizes[id] = 0 packedWith 0
    }

    // Sub-leaked object paths: sub-leaked id → list of parent leaked ids that can reach it
    val subLeakedObjectPaths = mutableMapOf<Long, MutableList<Long>>()

    // Leaked objects not yet processed in the secondary BFS
    val unprocessedLeakingIds = LongScatterSet()
    allLeakingObjectIds.elementSequence().forEach { unprocessedLeakingIds.add(it) }

    // Leaked objects found as sub-leaked (reachable through another leaked object)
    // These should not be enqueued as seeds themselves (their parent will find them)
    val foundAsSubLeaked = LongScatterSet()

    // Process found leaked object ids one at a time (sequential BFS)
    results.pathsToLeakingObjects.forEach { seedPathNode ->
      val seedId = seedPathNode.objectId
      unprocessedLeakingIds.remove(seedId)

      // If this leaked object was found as a sub-leaked during a previous seed's BFS,
      // skip it as a seed (it's already been accounted for).
      if (foundAsSubLeaked.contains(seedId)) {
        return@forEach
      }

      // BFS from this leaked object id
      val bfsQueue: ArrayDeque<Long> = ArrayDeque()

      // The seed itself: add to visitedSet and enqueue
      if (visitedSet.add(seedId)) {
        bfsQueue.add(seedId)
      }

      while (bfsQueue.isNotEmpty()) {
        val objectId = bfsQueue.poll()

        // Attribute this object's size to the seed (if computing retained size)
        if (computeRetainedHeapSize) {
          val shallowSize = objectSizeCalculator.computeSize(objectId)
          val current = retainedSizes.getOrDefault(seedId, 0 packedWith 0)
          retainedSizes[seedId] =
            (current.unpackAsFirstInt + shallowSize) packedWith (current.unpackAsSecondInt + 1)
        }

        val heapObject = try {
          graph.findObjectById(objectId)
        } catch (_: IllegalArgumentException) {
          continue
        }

        objectReferenceReader.read(heapObject).forEach { reference ->
          val refId = reference.valueObjectId
          if (refId == ValueHolder.NULL_REFERENCE) return@forEach

          // Check if this is another leaked object id that hasn't been processed yet
          if (unprocessedLeakingIds.contains(refId) && !foundAsSubLeaked.contains(refId)) {
            // Record as sub-leaked: this leaked object id is reachable from seedId
            subLeakedObjectPaths.getOrPut(refId) { mutableListOf() }.add(seedId)
            foundAsSubLeaked.add(refId)
            unprocessedLeakingIds.remove(refId)
            // Also add to visitedSet so it's counted as part of the BFS, but don't
            // enqueue its children (treat as leaf in sub-BFS, similar to Phase 1)
            // Actually: we SHOULD explore its children to find more sub-leaked objects
            // and to attribute their sizes to seedId. So enqueue it if not yet visited.
            if (visitedSet.add(refId)) {
              bfsQueue.add(refId)
            }
            return@forEach
          }

          // Skip objects already in R₀ or already visited by any seed's BFS
          if (!visitedSet.add(refId)) return@forEach

          bfsQueue.add(refId)
        }
      }

      // If not computing retained size and all leaked objects accounted for, stop early
      if (!computeRetainedHeapSize && unprocessedLeakingIds.size() == 0) {
        return@forEach // will naturally exit the forEach
      }
    }

    val updatedResults = PathFindingResults(
      pathsToLeakingObjects = results.pathsToLeakingObjects,
      visitedSet = visitedSet,
      subLeakedObjectPaths = subLeakedObjectPaths,
    )

    return retainedSizes to updatedResults
  }

  private fun List<ReferencePathNode>.toLongScatterSet(): LongScatterSet {
    val set = LongScatterSet()
    set.ensureCapacity(size)
    forEach { set.add(it.objectId) }
    return set
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
    // Set of leaked object ids that have been found so far
    val foundLeakingObjectIds = LongScatterSet()

    // Phase 1: BFS from GC roots, treating leaked objects as leaves
    visitingQueue@ while (queuesNotEmpty) {
      val node = poll()

      if (leakingObjectIds.contains(node.objectId)) {
        shortestPathsToLeakingObjects.add(node)
        foundLeakingObjectIds.add(node.objectId)
        val allFound = foundLeakingObjectIds.size() == leakingObjectIds.size()
        if (allFound && !computeRetainedHeapSize) {
          // Case A with no retained size needed: stop immediately
          break@visitingQueue
        }
        // Case A with retained size or not all found yet: continue draining to complete R₀
        // but treat leaked objects as leaves (do NOT enqueue their children)
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
    // Leaked objects will be the seeds for Phase 2 (computeRetainedSizes), so they
    // must NOT be pre-excluded from size attribution.
    leakingObjectIds.elementSequence().forEach { leakingId ->
      visitedSet.remove(leakingId)
    }

    return PathFindingResults(
      pathsToLeakingObjects = shortestPathsToLeakingObjects,
      visitedSet = visitedSet,
      subLeakedObjectPaths = emptyMap(), // populated later by computeRetainedSizes
    )
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
