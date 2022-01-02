@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark.internal

import java.util.ArrayDeque
import java.util.Deque
import shark.GcRoot
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.ThreadObject
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.IgnoredReferenceMatcher
import shark.LibraryLeakReferenceMatcher
import shark.OnAnalysisProgressListener
import shark.OnAnalysisProgressListener.Step.FINDING_DOMINATORS
import shark.OnAnalysisProgressListener.Step.FINDING_PATHS_TO_RETAINED_OBJECTS
import shark.PrimitiveType.INT
import shark.ReferenceMatcher
import shark.ReferencePattern.NativeGlobalVariablePattern
import shark.ReferenceReader
import shark.ThreadObjects
import shark.ValueHolder
import shark.filterFor
import shark.internal.PathFinder.VisitTracker.Dominated
import shark.internal.PathFinder.VisitTracker.Visited
import shark.internal.ReferencePathNode.ChildNode
import shark.internal.ReferencePathNode.RootNode
import shark.internal.ReferencePathNode.RootNode.LibraryLeakRootNode
import shark.internal.ReferencePathNode.RootNode.NormalRootNode
import shark.internal.hppc.LongScatterSet

/**
 * Not thread safe.
 *
 * Finds the shortest path from leaking references to a gc root, first ignoring references
 * identified as "to visit last" and then visiting them as needed if no path is
 * found.
 */
internal class PathFinder(
  private val graph: HeapGraph,
  private val listener: OnAnalysisProgressListener,
  private val objectReferenceReader: ReferenceReader<HeapObject>,
  referenceMatchers: List<ReferenceMatcher>
) {

  class PathFindingResults(
    val pathsToLeakingObjects: List<ReferencePathNode>,
    val dominatorTree: DominatorTree?
  )

  sealed class VisitTracker {

    abstract fun visited(
      objectId: Long,
      parentObjectId: Long
    ): Boolean

    class Dominated(expectedElements: Int) : VisitTracker() {
      /**
       * Tracks visited objecs and their dominator.
       * If an object is not in [dominatorTree] then it hasn't been enqueued yet.
       * If an object is in [dominatorTree] but not in [State.toVisitSet] nor [State.toVisitLastSet]
       * then it has already been dequeued.
       *
       * If an object is dominated by more than one GC root then its dominator is set to
       * [ValueHolder.NULL_REFERENCE].
       */
      val dominatorTree = DominatorTree(expectedElements)
      override fun visited(
        objectId: Long,
        parentObjectId: Long
      ): Boolean {
        return dominatorTree.updateDominated(objectId, parentObjectId)
      }
    }

    class Visited(expectedElements: Int) : VisitTracker() {
      /**
       * Set of visited objects.
       */
      private val visitedSet = LongScatterSet(expectedElements)
      override fun visited(
        objectId: Long,
        parentObjectId: Long
      ): Boolean {
        return !visitedSet.add(objectId)
      }
    }
  }

  private class State(
    val leakingObjectIds: LongScatterSet,
    val sizeOfObjectInstances: Int,
    val computeRetainedHeapSize: Boolean,
    val javaLangObjectId: Long,
    estimatedVisitedObjects: Int
  ) {

    /** Set of objects to visit */
    val toVisitQueue: Deque<ReferencePathNode> = ArrayDeque()

    /**
     * Objects to visit when [toVisitQueue] is empty. Should contain [JavaFrame] gc roots first,
     * then [LibraryLeakNode].
     */
    val toVisitLastQueue: Deque<ReferencePathNode> = ArrayDeque()

    /**
     * Enables fast checking of whether a node is already in the queue.
     */
    val toVisitSet = LongScatterSet()
    val toVisitLastSet = LongScatterSet()

    val queuesNotEmpty: Boolean
      get() = toVisitQueue.isNotEmpty() || toVisitLastQueue.isNotEmpty()

    val visitTracker = if (computeRetainedHeapSize) {
      Dominated(estimatedVisitedObjects)
    } else {
      Visited(estimatedVisitedObjects)
    }

    /**
     * A marker for when we're done exploring the graph of higher priority references and start
     * visiting the lower priority references, at which point we won't add any reference to
     * the high priority queue anymore.
     */
    var visitingLast = false
  }

  private val jniGlobalReferenceMatchers: Map<String, ReferenceMatcher>

  private val threadClassObjectIds: Set<Long> =
    graph.findClassByName(Thread::class.java.name)?.let { threadClass ->
      setOf(threadClass.objectId) + (threadClass.subclasses
        .map { it.objectId }
        .toSet())
    }?: emptySet()

  init {
    val jniGlobals = mutableMapOf<String, ReferenceMatcher>()
    referenceMatchers.filterFor(graph).forEach { referenceMatcher ->
      when (val pattern = referenceMatcher.pattern) {
        is NativeGlobalVariablePattern -> {
          jniGlobals[pattern.className] = referenceMatcher
        }
      }
    }
    this.jniGlobalReferenceMatchers = jniGlobals
  }

  fun findPathsFromGcRoots(
    leakingObjectIds: Set<Long>,
    computeRetainedHeapSize: Boolean
  ): PathFindingResults {
    listener.onAnalysisProgress(FINDING_PATHS_TO_RETAINED_OBJECTS)

    val objectClass = graph.findClassByName("java.lang.Object")
    val sizeOfObjectInstances = determineSizeOfObjectInstances(objectClass, graph)
    val javaLangObjectId = objectClass?.objectId ?: -1

    // Estimate of how many objects we'll visit. This is a conservative estimate, we should always
    // visit more than that but this limits the number of early array growths.
    val estimatedVisitedObjects = (graph.instanceCount / 2).coerceAtLeast(4)

    val state = State(
      leakingObjectIds = leakingObjectIds.toLongScatterSet(),
      sizeOfObjectInstances = sizeOfObjectInstances,
      computeRetainedHeapSize = computeRetainedHeapSize,
      javaLangObjectId = javaLangObjectId,
      estimatedVisitedObjects = estimatedVisitedObjects
    )

    return state.findPathsFromGcRoots()
  }

  private fun determineSizeOfObjectInstances(
    objectClass: HeapClass?,
    graph: HeapGraph
  ): Int {
    return if (objectClass != null) {
      // In Android 16 ClassDumpRecord.instanceSize for java.lang.Object can be 8 yet there are 0
      // fields. This is likely because there is extra per instance data that isn't coming from
      // fields in the Object class. See #1374
      val objectClassFieldSize = objectClass.readFieldsByteSize()

      // shadow$_klass_ (object id) + shadow$_monitor_ (Int)
      val sizeOfObjectOnArt = graph.identifierByteSize + INT.byteSize
      if (objectClassFieldSize == sizeOfObjectOnArt) {
        sizeOfObjectOnArt
      } else {
        0
      }
    } else {
      0
    }
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
    visitingQueue@ while (queuesNotEmpty) {
      val node = poll()
      if (leakingObjectIds.contains(node.objectId)) {
        shortestPathsToLeakingObjects.add(node)
        // Found all refs, stop searching (unless computing retained size)
        if (shortestPathsToLeakingObjects.size == leakingObjectIds.size()) {
          if (computeRetainedHeapSize) {
            listener.onAnalysisProgress(FINDING_DOMINATORS)
          } else {
            break@visitingQueue
          }
        }
      }

      val heapObject = graph.findObjectById(node.objectId)
      objectReferenceReader.read(heapObject).forEach { reference ->
        val newNode = ChildNode(
          objectId = reference.valueObjectId,
          parent = node,
          lazyDetailsResolver = reference.lazyDetailsResolver
        )
        enqueue(newNode, isLowPriority = reference.isLowPriority)
      }
    }
    return PathFindingResults(
      shortestPathsToLeakingObjects,
      if (visitTracker is Dominated) visitTracker.dominatorTree else null
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
    val gcRoots = sortedGcRoots()

    gcRoots.forEach { (objectRecord, gcRoot) ->
      when (gcRoot) {
        is ThreadObject -> {
          // TODO have a wrapper for FieldInstanceReader that recognises thread instances,
          // delegates to FieldInstanceReader but filters thread local values to be lower priority.
          // We deprioritize thread objects because on Lollipop the thread local values are stored
          // as a field.
          enqueue(NormalRootNode(gcRoot.id, gcRoot), isLowPriority = true)
        }
        // Note: in sortedGcRoots we already filter out any java frame that has an associated
        // thread.
        is JavaFrame -> {
          enqueue(NormalRootNode(gcRoot.id, gcRoot), isLowPriority = true)
        }
        is JniGlobal -> {
          val referenceMatcher = when (objectRecord) {
            is HeapClass -> jniGlobalReferenceMatchers[objectRecord.name]
            is HeapInstance -> jniGlobalReferenceMatchers[objectRecord.instanceClassName]
            is HeapObjectArray -> jniGlobalReferenceMatchers[objectRecord.arrayClassName]
            is HeapPrimitiveArray -> jniGlobalReferenceMatchers[objectRecord.arrayClassName]
          }
          if (referenceMatcher !is IgnoredReferenceMatcher) {
            if (referenceMatcher is LibraryLeakReferenceMatcher) {
              enqueue(LibraryLeakRootNode(gcRoot.id, gcRoot, referenceMatcher), isLowPriority =true)
            } else {
              enqueue(NormalRootNode(gcRoot.id, gcRoot), isLowPriority = false)
            }
          }
        }
        else -> enqueue(NormalRootNode(gcRoot.id, gcRoot), isLowPriority = false)
      }
    }
  }

  /**
   * Sorting GC roots to get stable shortest path
   * Once sorted all ThreadObject Gc Roots are located before JavaLocalPattern Gc Roots.
   * This ensures ThreadObjects are visited before JavaFrames, and threadsBySerialNumber can be
   * built before JavaFrames.
   */
  private fun sortedGcRoots(): List<Pair<HeapObject, GcRoot>> {
    val rootClassName: (HeapObject) -> String = { graphObject ->
      when (graphObject) {
        is HeapClass -> {
          graphObject.name
        }
        is HeapInstance -> {
          graphObject.instanceClassName
        }
        is HeapObjectArray -> {
          graphObject.arrayClassName
        }
        is HeapPrimitiveArray -> {
          graphObject.arrayClassName
        }
      }
    }

    val threadSerialNumbers =
      ThreadObjects.getThreadObjects(graph).map { it.threadSerialNumber }.toSet()

    return graph.gcRoots
      .filter { gcRoot ->
        // GC roots sometimes reference objects that don't exist in the heap dump
        // See https://github.com/square/leakcanary/issues/1516
        graph.objectExists(gcRoot.id) &&
          // Only include java frames that do not have a corresponding ThreadObject.
          // JavaLocalReferenceReader will insert the other java frames.
          !(gcRoot is JavaFrame && gcRoot.threadSerialNumber in threadSerialNumbers)
      }
      .map { graph.findObjectById(it.id) to it }
      .sortedWith { (graphObject1, root1), (graphObject2, root2) ->
        // Sorting based on pattern name first. In reverse order so that ThreadObject is before JavaLocalPattern
        val gcRootTypeComparison = root2::class.java.name.compareTo(root1::class.java.name)
        if (gcRootTypeComparison != 0) {
          gcRootTypeComparison
        } else {
          rootClassName(graphObject1).compareTo(rootClassName(graphObject2))
        }
      }
  }

  @Suppress("ReturnCount")
  private fun State.enqueue(
    node: ReferencePathNode,
    isLowPriority: Boolean
  ) {
    if (node.objectId == ValueHolder.NULL_REFERENCE) {
      return
    }

    val visitLast = visitingLast || isLowPriority

    val parentObjectId = when(node) {
      is RootNode -> ValueHolder.NULL_REFERENCE
      is ChildNode -> node.parent.objectId
    }

    // Note: when computing dominators, this has a side effects of updating
    // the dominator for node.objectId.
    val alreadyEnqueued = visitTracker.visited(node.objectId, parentObjectId)

    if (alreadyEnqueued) {
      // Has already been enqueued and would be added to visit last => don't enqueue.
      if (visitLast) {
        return
      }
      // Has already been enqueued and exists in the to visit set => don't enqueue
      if (toVisitSet.contains(node.objectId)) {
        return
      }
      // Has already been enqueued, is not in toVisitSet, is not in toVisitLast => has been visited
      if (!toVisitLastSet.contains(node.objectId)) {
        return
      }
    }

    // Because of the checks and return statements right before, from this point on, if
    // alreadyEnqueued then it's currently enqueued in the visit last set.
    if (alreadyEnqueued) {
      // Move from "visit last" to "visit first" queue.
      toVisitQueue.add(node)
      toVisitSet.add(node.objectId)
      val nodeToRemove = toVisitLastQueue.first { it.objectId == node.objectId }
      toVisitLastQueue.remove(nodeToRemove)
      toVisitLastSet.remove(node.objectId)
      return
    }

    val isLeakingObject = leakingObjectIds.contains(node.objectId)

    if (!isLeakingObject) {
      val skip = when (val graphObject = graph.findObjectById(node.objectId)) {
        is HeapClass -> false
        is HeapInstance ->
          when {
            graphObject.isPrimitiveWrapper -> true
            graphObject.instanceClassName == "java.lang.String" -> {
              // We ignore the fact that String references a value array to avoid having
              // to read the string record and find the object id for that array, since we know
              // it won't be interesting anyway.
              // That also means the value array isn't added to the dominator tree, so we need to
              // add that back when computing shallow size in ShallowSizeCalculator.
              // Another side effect is that if the array is referenced elsewhere, we might
              // double count its side.
              true
            }
            // Don't skip empty thread instances as we might add java frames to those.
            graphObject.instanceClassId in threadClassObjectIds -> false
            graphObject.instanceClass.instanceByteSize <= sizeOfObjectInstances -> true
            graphObject.instanceClass.classHierarchy.all { heapClass ->
              heapClass.objectId == javaLangObjectId || !heapClass.hasReferenceInstanceFields
            } -> true
            else -> false
          }
        is HeapObjectArray -> when {
          graphObject.isSkippablePrimitiveWrapperArray -> {
            // Same optimization as we did for String above, as we know primitive wrapper arrays
            // aren't interesting.
            true
          }
          else -> false
        }
        is HeapPrimitiveArray -> true
      }
      if (skip) {
        return
      }
    }
    if (visitLast) {
      toVisitLastQueue.add(node)
      toVisitLastSet.add(node.objectId)
    } else {
      toVisitQueue.add(node)
      toVisitSet.add(node.objectId)
    }
  }
}

private val skippablePrimitiveWrapperArrayTypes = setOf(
  Boolean::class,
  Char::class,
  Float::class,
  Double::class,
  Byte::class,
  Short::class,
  Int::class,
  Long::class
).map { it.javaObjectType.name + "[]" }

internal val HeapObjectArray.isSkippablePrimitiveWrapperArray: Boolean
  get() = arrayClassName in skippablePrimitiveWrapperArrayTypes

