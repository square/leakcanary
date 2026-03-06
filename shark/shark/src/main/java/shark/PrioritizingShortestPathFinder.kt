@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import androidx.collection.MutableLongLongMap
import java.util.ArrayDeque
import java.util.Deque
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

/**
 * Not thread safe.
 *
 * Finds the shortest path from each leaking object to a GC root, using a prioritized BFS.
 * High-priority references are visited first; low-priority references (e.g. library leak
 * matchers) are deferred to a second queue and only visited if needed.
 *
 * ## Phase 1 — GC root BFS
 *
 * BFS from GC roots where **leaked objects are treated as leaf nodes**: when a leaked object
 * is reached its path is recorded, but its outgoing references are never enqueued. The visited
 * set at the end of this phase is **R₀** — all objects reachable from GC roots without passing
 * through any leaked object.
 *
 * Stopping depends on [Factory.objectSizeCalculatorFactory]:
 * - `null` and all N found before queues empty → stop immediately (R₀ need not be complete)
 * - non-null or queues empty before all N found → drain both queues to build a complete R₀,
 *   then Phase 2 handles finding remaining leaked objects and sizing.
 *
 * ## Phase 2 — retained size BFS
 *
 * Processes found leaked object ids **one at a time**. For each seed:
 * - BFS from it, extending R₀ as objects are visited
 * - Shallow size of each newly visited object is attributed to this seed
 *   (only when [Factory.objectSizeCalculatorFactory] is non-null)
 * - Other Phase 1 seeds encountered are skipped (treated as leaves — they will be processed
 *   in their own turn, preserving first-BFS-wins attribution)
 * - Leaked object ids not found in Phase 1 that are encountered become **sub-leaked** objects
 *   of the current seed, and their subgraphs continue to be explored under the current seed
 *
 * If [Factory.objectSizeCalculatorFactory] is null, Phase 2 only runs to find any remaining
 * leaked objects; it stops as soon as all leaked object ids have been accounted for.
 */
class PrioritizingShortestPathFinder private constructor(
  private val graph: HeapGraph,
  private val listener: Event.Listener,
  private val objectReferenceReader: ReferenceReader<HeapObject>,
  private val gcRootProvider: GcRootProvider,
  private val objectSizeCalculator: ObjectSizeCalculator?,
) : ShortestPathFinder {

  class Factory(
    private val listener: Event.Listener,
    private val referenceReaderFactory: ReferenceReader.Factory<HeapObject>,
    private val gcRootProvider: GcRootProvider,
    /**
     * When non-null, Phase 1 drains both queues to build a complete R₀, and Phase 2 computes
     * retained heap size for each leaked object id.
     * When null, Phase 1 stops as soon as all leaked objects are found, and Phase 2 only
     * runs to discover sub-leaked objects.
     * Called once per [HeapGraph] to create the calculator.
     */
    val objectSizeCalculatorFactory: ObjectSizeCalculator.Factory? = null,
  ) : ShortestPathFinder.Factory {
    override fun createFor(heapGraph: HeapGraph): ShortestPathFinder {
      return PrioritizingShortestPathFinder(
        graph = heapGraph,
        listener = listener,
        objectReferenceReader = referenceReaderFactory.createFor(heapGraph),
        gcRootProvider = gcRootProvider,
        objectSizeCalculator = objectSizeCalculatorFactory?.createFor(heapGraph),
      )
    }
  }

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
    val toVisitQueue: Deque<ReferencePathNode> = ArrayDeque()
    val toVisitLastQueue: Deque<ReferencePathNode> = ArrayDeque()
    val toVisitSet = LongScatterSet()
    val toVisitLastSet = LongScatterSet()

    val queuesNotEmpty: Boolean
      get() = toVisitQueue.isNotEmpty() || toVisitLastQueue.isNotEmpty()

    /** Tracks all objects visited during Phase 1 (R₀). Shared with Phase 2. */
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
    val foundLeakingObjectIds = LongScatterSet()

    // Phase 1: BFS from GC roots, leaked objects treated as leaves.
    visitingQueue@ while (queuesNotEmpty) {
      val node = poll()

      if (leakingObjectIds.contains(node.objectId)) {
        shortestPathsToLeakingObjects.add(node)
        foundLeakingObjectIds.add(node.objectId)
        val allFound = foundLeakingObjectIds.size() == leakingObjectIds.size()
        if (allFound && objectSizeCalculator == null) {
          // All leaked objects found and retained size not needed: stop immediately.
          break@visitingQueue
        }
        // Treat leaked objects as leaves: do NOT enqueue their children.
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

    // Phase 2: Sequential BFS from each found leaked object id, computing retained sizes
    // and discovering sub-leaked objects (leaked objects only reachable through other leaks).
    val retainedSizes = if (objectSizeCalculator != null) {
      MutableLongLongMap(leakingObjectIds.size()).also { map ->
        leakingObjectIds.elementSequence().forEach { id -> map[id] = 0 packedWith 0 }
      }
    } else {
      null
    }

    val subLeakedObjectPaths = mutableMapOf<Long, MutableList<Long>>()

    // Leaked objects not found in Phase 1: only reachable through other leaked objects.
    val notYetFoundLeakingIds = mutableSetOf<Long>()
    leakingObjectIds.elementSequence().forEach { id ->
      if (!foundLeakingObjectIds.contains(id)) notYetFoundLeakingIds.add(id)
    }

    // Phase 1 seeds not yet processed — used to detect when another Phase 1 seed is
    // encountered during the current seed's BFS (treat it as a leaf, let it be its own seed).
    val unprocessedSeedIds = LongScatterSet().also { set ->
      foundLeakingObjectIds.elementSequence().forEach { set.add(it) }
    }

    while (unprocessedSeedIds.size() > 0) {
      val seedId = unprocessedSeedIds.elementSequence().first()
      unprocessedSeedIds.remove(seedId)

      val bfsQueue = ArrayDeque<Long>()
      bfsQueue.add(seedId)

      while (bfsQueue.isNotEmpty()) {
        val objectId = bfsQueue.poll()

        if (retainedSizes != null && objectSizeCalculator != null) {
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

          // Another Phase 1 seed: treat as leaf. It will be processed as its own seed and
          // must not have its subgraph attributed to the current seed.
          if (refId in unprocessedSeedIds) return@forEach

          // A leaked object only reachable through other leaked objects (not found in Phase 1):
          // record as sub-leaked, remove from the not-yet-found set, and continue exploring
          // its subgraph under the current seed.
          if (refId in notYetFoundLeakingIds) {
            notYetFoundLeakingIds.remove(refId)
            subLeakedObjectPaths.getOrPut(seedId) { mutableListOf() }.add(refId)
            if (visitedSet.add(refId)) {
              bfsQueue.add(refId)
            }
            return@forEach
          }

          // Skip objects already in R₀ or already attributed to a previous seed.
          if (!visitedSet.add(refId)) return@forEach
          bfsQueue.add(refId)
        }
      }

      // If we only need to find remaining leaked objects (not compute sizes), stop as soon as
      // all leaked object ids have been found.
      if (objectSizeCalculator == null && notYetFoundLeakingIds.isEmpty()) break
    }

    return PathFindingResults(
      pathsToLeakingObjects = shortestPathsToLeakingObjects,
      retainedSizes = retainedSizes,
      subLeakedObjectPaths = subLeakedObjectPaths,
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
