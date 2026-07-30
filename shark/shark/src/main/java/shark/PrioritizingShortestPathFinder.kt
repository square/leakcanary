@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import androidx.collection.LongList
import androidx.collection.LongObjectMap
import androidx.collection.MutableLongList
import androidx.collection.MutableLongObjectMap
import androidx.collection.emptyLongObjectMap
import java.util.ArrayDeque
import java.util.Deque
import shark.PrioritizingShortestPathFinder.Event.StartedFindingPathsToRetainedObjects
import shark.internal.HeapObjectIdSet
import shark.internal.ReferencePathNode
import shark.internal.ReferencePathNode.ChildNode
import shark.internal.ReferencePathNode.RootNode.LibraryLeakRootNode
import shark.internal.ReferencePathNode.RootNode.NormalRootNode
import shark.internal.hppc.LongDeque
import shark.internal.hppc.LongScatterSet
import shark.internal.invalidObjectIdErrorMessage

/**
 * Not thread safe.
 *
 * Finds the shortest path from leaking references to a gc root, first ignoring references
 * identified as "to visit last" and then visiting them as needed if no path is
 * found.
 *
 * Also computes the retained heap size of each leaking object, when
 * [Factory.objectSizeCalculatorFactory] is provided.
 *
 * ## Phase 1 — traversal from GC roots
 *
 * BFS from GC roots where **leaking objects are treated as leaves**: when a leaking object is
 * dequeued its path is recorded, but its outgoing references are not enqueued. The visited set
 * at the end of this phase is therefore **R₀**: every object reachable from GC roots *without*
 * going through a leaking object.
 *
 * A consequence is that the path reported for a leaking object is the shortest path that doesn't
 * go through another leaking object, which can be longer than the shortest path overall. That's
 * the more actionable of the two: a path that goes through another leaking object tells you to go
 * fix that other leak first, and that other leak has a path of its own in the results.
 *
 * Phase 1 stops early (leaving R₀ incomplete) only when retained sizes aren't needed and all
 * leaking objects have been found, i.e. when there's nothing left for phase 2 to do.
 *
 * ## Phase 2 — traversal from leaking objects
 *
 * Phase 2 explores what phase 1 deliberately left out: the subgraphs hanging off leaking
 * objects. Leaking objects are visited one at a time, in the order phase 1 found them, each
 * BFS extending the shared visited set. Phase 2 serves two purposes:
 *
 * - **Retained size**: every object visited here is reachable only through leaking objects
 *   (it's not in R₀), so its shallow size is attributed to the leaking object currently being
 *   explored. An object reachable from more than one leaking object is attributed to the
 *   first one that reaches it, so sizes never double count and always sum to the size of the
 *   subgraph retained by the leaking objects as a group. It also means such an object is credited
 *   in full to the leaking object phase 1 found first, so fixing that one leak on its own frees
 *   less than the size reported for it, and which of two leaking objects gets credited for it
 *   depends on the order phase 1 found them.
 *
 * - **Leaking objects reachable only through other leaking objects**: since phase 1 stops at
 *   leaking objects, a leaking object nested under another one is never found there. Phase 2
 *   picks it up and reports it as a *sub leaked object* of the leaking object it was found
 *   under: it's surfaced as a label on that object's leak trace rather than as a leak of its
 *   own. Leaking objects found in neither phase are unreachable.
 *
 * Phase 2 is skipped entirely when there are no retained sizes to compute and no missing
 * leaking objects to find.
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
     * When non null, [PathFindingResults.retainedSizes] is computed, which requires traversing
     * the entire graph reachable from GC roots. When null, retained sizes aren't computed and
     * the traversal stops as soon as all leaking objects have been found.
     *
     * Called once per [HeapGraph].
     */
    private val objectSizeCalculatorFactory: ObjectSizeCalculator.Factory? = null,
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

  // TODO Enum or sealed? class makes it possible to report progress. Enum
  // provides ordering of events.
  sealed interface Event {
    object StartedFindingPathsToRetainedObjects : Event

    @Deprecated("Event not sent anymore")
    object StartedFindingDominators : Event

    fun interface Listener {
      fun onEvent(event: Event)
    }
  }

  private class State(
    graph: HeapGraph,
    val leakingObjectIds: LongScatterSet,
  ) {

    /** Set of objects to visit */
    val toVisitQueue: Deque<ReferencePathNode> = ArrayDeque()

    /**
     * Objects to visit when [toVisitQueue] is empty.
     */
    val toVisitLastQueue: Deque<ReferencePathNode> = ArrayDeque()

    /**
     * The ids of the nodes in [toVisitLastQueue], so that [enqueue] can tell an object that is
     * waiting in the low priority queue (and should be promoted) from one that was already visited,
     * without scanning the queue.
     *
     * There's deliberately no matching set for [toVisitQueue]: an object is added to exactly one of
     * the two queues and removed from it when polled, so it's never in both, and therefore being in
     * this set already means not being in [toVisitQueue].
     *
     * Unlike [visitedSet] this stays a set of ids keyed by hash: it only ever holds references
     * matched by a library leak pattern, java locals and matched GC roots, which peaks in the dozens
     * on real heap dumps, where a bit per object in the dump would cost hundreds of kilobytes.
     */
    val toVisitLastSet = LongScatterSet()

    val queuesNotEmpty: Boolean
      get() = toVisitQueue.isNotEmpty() || toVisitLastQueue.isNotEmpty()

    /**
     * Set of visited objects. At the end of phase 1 this is R₀, then phase 2 keeps extending it
     * with the objects reachable only through leaking objects.
     */
    val visitedSet = HeapObjectIdSet(graph)

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
    val state = State(
      graph = graph,
      leakingObjectIds = leakingObjectIds.toLongScatterSet(),
    )

    // Sent after the traversal state is allocated, so that a listener which samples memory on every
    // event sees that allocation. The visited set is the largest thing the analysis allocates and
    // it's unreachable again by the time the next event is sent, so sending this event first would
    // hide it from HprofRetainedHeapPerfTest entirely.
    listener.onEvent(StartedFindingPathsToRetainedObjects)

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
    // Ordered: phase 2 explores leaking objects in the order phase 1 found them, which keeps
    // retained size attribution stable across runs. A list rather than a set because phase 1
    // dequeues any given object at most once, so this can't hold duplicates.
    val foundLeakingObjectIds = MutableLongList(leakingObjectIds.size())

    visitingQueue@ while (queuesNotEmpty) {
      val node = poll()

      if (leakingObjectIds.contains(node.objectId)) {
        shortestPathsToLeakingObjects.add(node)
        foundLeakingObjectIds += node.objectId
        if (foundLeakingObjectIds.size == leakingObjectIds.size() &&
          objectSizeCalculator == null
        ) {
          // Found all leaking objects and we don't need retained sizes: phase 2 has nothing
          // left to do, so R₀ doesn't need to be complete.
          break@visitingQueue
        }
        // Leaking objects are leaves: their references are explored in phase 2 instead, so that
        // R₀ only holds objects reachable without going through a leaking object.
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

    val phase2 = Phase2(
      leakingObjectIds = leakingObjectIds,
      foundLeakingObjectIds = foundLeakingObjectIds,
      visitedSet = visitedSet
    )
    phase2.run()

    return PathFindingResults(
      pathsToLeakingObjects = shortestPathsToLeakingObjects,
      retainedSizes = phase2.retainedSizes,
      subLeakedObjectsByLeakedObject = phase2.subLeakedObjectsByLeakedObject,
    )
  }

  /**
   * Traversal of the subgraphs that hang off the leaking objects found in phase 1. See the
   * [PrioritizingShortestPathFinder] class doc.
   */
  private inner class Phase2(
    private val leakingObjectIds: LongScatterSet,
    private val foundLeakingObjectIds: LongList,
    private val visitedSet: HeapObjectIdSet
  ) {

    /** Leaking object id to retained size, null when retained sizes aren't computed. */
    var retainedSizes: LongObjectMap<Retained>? = null
      private set

    var subLeakedObjectsByLeakedObject: LongObjectMap<LongArray> = emptyLongObjectMap()
      private set

    /**
     * Leaking objects that phase 1 didn't find: they're either only reachable through another
     * leaking object, or not reachable at all.
     */
    private val missingLeakingObjectIds = LongScatterSet().apply {
      leakingObjectIds.elementSequence().forEach { objectId ->
        if (!foundLeakingObjectIds.contains(objectId)) {
          add(objectId)
        }
      }
    }

    private val bfsQueue = LongDeque()

    /** Retained byte size of the leaking object currently being explored. */
    private var retainedSize = 0L

    /** Retained object count of the leaking object currently being explored. */
    private var retainedCount = 0

    fun run() {
      if (objectSizeCalculator == null && missingLeakingObjectIds.size() == 0) {
        // Nothing to size and nothing left to find.
        return
      }
      val sizes = if (objectSizeCalculator != null) {
        MutableLongObjectMap<Retained>(foundLeakingObjectIds.size).also { retainedSizes = it }
      } else {
        null
      }
      // Only ever holds an entry for a leaking object nested under another leaking object, which
      // is rare, so this starts empty and stays empty in the common case.
      val subLeakedObjects = MutableLongObjectMap<MutableLongList>(0)

      for (index in 0 until foundLeakingObjectIds.size) {
        val leakingObjectId = foundLeakingObjectIds[index]
        retainedSize = 0L
        retainedCount = 0
        // The leaking object itself is retained by itself. It's already in visitedSet (phase 1
        // added it when enqueueing it) so it can't be counted again by another leaking object.
        accumulate(leakingObjectId)
        bfsQueue += leakingObjectId

        while (bfsQueue.isNotEmpty()) {
          visitReferences(bfsQueue.poll(), leakingObjectId, subLeakedObjects)
          if (objectSizeCalculator == null && missingLeakingObjectIds.size() == 0) {
            // Found the last missing leaking object and we don't need retained sizes.
            publishSubLeakedObjects(subLeakedObjects)
            return
          }
        }
        sizes?.put(
          leakingObjectId,
          Retained(
            heapSize = retainedSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().bytes,
            objectCount = retainedCount
          )
        )
      }
      publishSubLeakedObjects(subLeakedObjects)
    }

    private fun publishSubLeakedObjects(subLeakedObjects: MutableLongObjectMap<MutableLongList>) {
      if (subLeakedObjects.isEmpty()) {
        return
      }
      subLeakedObjectsByLeakedObject =
        MutableLongObjectMap<LongArray>(subLeakedObjects.size).apply {
          subLeakedObjects.forEach { leakingObjectId, subLeakedObjectIds ->
            put(leakingObjectId, LongArray(subLeakedObjectIds.size) { subLeakedObjectIds[it] })
          }
        }
    }

    private fun visitReferences(
      objectId: Long,
      leakingObjectId: Long,
      subLeakedObjects: MutableLongObjectMap<MutableLongList>
    ) {
      val heapObject = try {
        graph.findObjectById(objectId)
      } catch (objectIdNotFound: IllegalArgumentException) {
        // This should never happen (a heap should only have references to objects that exist)
        // but when it does happen, let's at least say which object we were looking at.
        throw RuntimeException(
          "Failed to find object id $objectId, reached from leaking object" +
            " id $leakingObjectId", objectIdNotFound
        )
      }
      objectReferenceReader.read(heapObject).forEach { reference ->
        val referenceObjectId = reference.valueObjectId
        if (missingLeakingObjectIds.remove(referenceObjectId)) {
          // A leaking object that's only reachable through leaking objects. Reported as a label
          // on the leak trace of the leaking object we reached it from.
          subLeakedObjects.getOrPut(leakingObjectId) { MutableLongList(1) } += referenceObjectId
        }
        if (!visitedSet.add(referenceObjectId)) {
          // Either in R₀, or already attributed to a leaking object explored before this one.
          return@forEach
        }
        accumulate(referenceObjectId)
        // A leaf object has no references left to explore, its references were all surfaced by
        // the reference reader already. Leaking objects are never treated as leaves, we need to
        // explore their subgraph to find nested leaking objects.
        if (!reference.isLeafObject || referenceObjectId in leakingObjectIds) {
          bfsQueue += referenceObjectId
        }
      }
    }

    private fun accumulate(objectId: Long) {
      if (objectSizeCalculator == null) {
        return
      }
      retainedSize += objectSizeCalculator.computeSize(objectId)
      retainedCount++
    }
  }

  private fun State.poll(): ReferencePathNode {
    return if (!visitingLast && !toVisitQueue.isEmpty()) {
      toVisitQueue.poll()
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
        // Already visited and waiting in the low priority queue: it can be reached at a higher
        // priority than we thought, so move it. Being in toVisitLastSet also means not being in
        // toVisitQueue, since an object is only ever in one of the two queues.
        val bumpPriority = !visitLast && node.objectId in toVisitLastSet

        if (bumpPriority) {
          // Move from "visit last" to "visit first" queue.
          toVisitQueue.add(node)
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
      }
    }
  }
}
