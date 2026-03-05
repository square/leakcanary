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
 * ## Phase 1 — GC root BFS ([findShortestPathsFromGcRoots])
 *
 * BFS from GC roots where **leaked objects are treated as leaf nodes**: when a leaked object
 * is reached its path is recorded, but its outgoing references are never enqueued. The visited
 * set at the end of this phase is **R₀** — all objects reachable from GC roots without passing
 * through any leaked object.
 *
 * Stopping depends on [Factory.computeRetainedHeapSize]:
 * - `false` and all N found before queues empty → stop immediately (R₀ need not be complete)
 * - `true` or queues empty before all N found → drain both queues to build a complete R₀,
 *   then [computeRetainedSizes] handles finding remaining leaked objects and sizing.
 *
 * ## Phase 2 — [computeRetainedSizes]
 *
 * Processes found leaked object ids **one at a time**. For each seed:
 * - BFS from it, extending R₀ as objects are visited
 * - Shallow size of each newly visited object is attributed to this seed
 * - Other Phase 1 seeds encountered are skipped (treated as leaves — they will be processed
 *   in their own turn, preserving first-BFS-wins attribution)
 * - Leaked object ids not found in Phase 1 that are encountered become **sub-leaked** objects
 *   of the current seed, and their subgraphs continue to be explored under the current seed
 *
 * If `computeRetainedHeapSize=false`, Phase 2 only runs to find any remaining leaked objects;
 * it stops as soon as all leaked object ids have been accounted for.
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
     * When true, [computeRetainedSizes] will compute retained heap size for each leaked object
     * id. When false, [findShortestPathsFromGcRoots] stops as soon as all leaked objects are
     * found, and [computeRetainedSizes] only runs to discover sub-leaked objects.
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

  sealed interface Event {
    object StartedFindingPathsToRetainedObjects : Event

    fun interface Listener {
      fun onEvent(event: Event)
    }
  }

  /**
   * Result of [computeRetainedSizes].
   *
   * @property retainedSizes map of leaked object id → packed (retainedBytes, retainedCount).
   *   Only populated when [Factory.computeRetainedHeapSize] is true.
   * @property subLeakedObjectPaths map of sub-leaked object id → list of parent leaked object
   *   ids that can reach it. Sub-leaked objects are reachable from GC roots only through another
   *   leaked object, so they are reported as labels on the parent's leak trace rather than as
   *   independent leaks.
   */
  class RetainedSizeResults(
    val retainedSizes: MutableLongLongMap,
    val subLeakedObjectPaths: Map<Long, List<Long>>,
  )

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

    /**
     * Tracks all objects enqueued during Phase 1. After Phase 1, leaked object ids are removed
     * so that R₀ does not include them — they are seeds for Phase 2 and must not be pre-excluded
     * from retained size attribution.
     */
    val visitedSet = LongScatterSet(estimatedVisitedObjects)

    var visitingLast = false
  }

  override fun findShortestPathsFromGcRoots(
    leakingObjectIds: Set<Long>
  ): PathFindingResults {
    listener.onEvent(StartedFindingPathsToRetainedObjects)
    val estimatedVisitedObjects = (graph.instanceCount / 2).coerceAtLeast(4)

    val state = State(
      leakingObjectIds = leakingObjectIds.toLongScatterSet(),
      estimatedVisitedObjects = estimatedVisitedObjects
    )

    return state.findPathsFromGcRoots()
  }

  /**
   * Phase 2: sequential BFS from each found leaked object id, computing retained sizes and
   * discovering sub-leaked objects.
   *
   * Must be called after [findShortestPathsFromGcRoots]. The [results] visitedSet (R₀) is
   * extended in-place as new objects are discovered.
   *
   * Retained size is computed for the **leaked object ids** (objects passed to
   * [findShortestPathsFromGcRoots]), not the "first LEAKING node" determined by object
   * inspection. This avoids the R₀ bug: nodes on the path to a leaked object are in R₀, so
   * starting retained size BFS from them would immediately exclude all their outgoing refs.
   *
   * Attribution rule: an object's shallow size is attributed to the **first leaked object id
   * whose BFS reaches it**. Since seeds are processed sequentially and share the visitedSet,
   * objects found by seed N are unavailable to seeds N+1, N+2, etc.
   */
  fun computeRetainedSizes(
    results: PathFindingResults,
    objectSizeCalculator: ObjectSizeCalculator,
  ): RetainedSizeResults {
    val visitedSet = results.visitedSet

    // Phase 1 seeds: leaked objects found directly from GC roots.
    val phase1Seeds = results.pathsToLeakingObjects.map { it.objectId }.toHashSet()

    // Leaked objects not found in Phase 1: only reachable through other leaked objects.
    // These will be discovered during the sequential BFS below.
    val notYetFoundLeakingIds = mutableSetOf<Long>()
    results.leakingObjectIds.elementSequence().forEach { id ->
      if (id !in phase1Seeds) notYetFoundLeakingIds.add(id)
    }

    // Retained sizes: leaked object id → packed (retainedBytes, retainedCount).
    val retainedSizes = MutableLongLongMap(results.leakingObjectIds.size())
    results.leakingObjectIds.elementSequence().forEach { id ->
      retainedSizes[id] = 0 packedWith 0
    }

    val subLeakedObjectPaths = mutableMapOf<Long, MutableList<Long>>()

    // Phase 1 seeds not yet processed — used to detect when another Phase 1 seed is
    // encountered during the current seed's BFS (treat it as a leaf, let it be its own seed).
    val unprocessedSeedIds = phase1Seeds.toMutableSet()

    for (seedPathNode in results.pathsToLeakingObjects) {
      val seedId = seedPathNode.objectId
      unprocessedSeedIds.remove(seedId)

      val bfsQueue = ArrayDeque<Long>()
      // The seed itself was removed from R₀ at the end of Phase 1; add it back now.
      visitedSet.add(seedId)
      bfsQueue.add(seedId)

      while (bfsQueue.isNotEmpty()) {
        val objectId = bfsQueue.poll()

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

          // Another Phase 1 seed: treat as leaf. It will be processed as its own seed and
          // must not have its subgraph attributed to the current seed.
          if (refId in unprocessedSeedIds) return@forEach

          // A leaked object only reachable through other leaked objects (not found in Phase 1):
          // record as sub-leaked, remove from the not-yet-found set, and continue exploring
          // its subgraph under the current seed.
          if (refId in notYetFoundLeakingIds) {
            notYetFoundLeakingIds.remove(refId)
            subLeakedObjectPaths.getOrPut(refId) { mutableListOf() }.add(seedId)
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
      if (!computeRetainedHeapSize && notYetFoundLeakingIds.isEmpty()) break
    }

    return RetainedSizeResults(retainedSizes, subLeakedObjectPaths)
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

    visitingQueue@ while (queuesNotEmpty) {
      val node = poll()

      if (leakingObjectIds.contains(node.objectId)) {
        shortestPathsToLeakingObjects.add(node)
        foundLeakingObjectIds.add(node.objectId)
        val allFound = foundLeakingObjectIds.size() == leakingObjectIds.size()
        if (allFound && !computeRetainedHeapSize) {
          // All leaked objects found and retained size not needed: stop immediately.
          break@visitingQueue
        }
        // Treat leaked objects as leaves: do NOT enqueue their children.
        continue@visitingQueue
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
        enqueue(
          node = newNode,
          isLowPriority = reference.isLowPriority,
          isLeafObject = reference.isLeafObject
        )
      }
    }

    // Remove leaked object ids from R₀. They will be seeds in computeRetainedSizes and must
    // not be pre-excluded from retained size attribution.
    leakingObjectIds.elementSequence().forEach { visitedSet.remove(it) }

    return PathFindingResults(
      pathsToLeakingObjects = shortestPathsToLeakingObjects,
      visitedSet = visitedSet,
      leakingObjectIds = leakingObjectIds,
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
