/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package shark.internal

import shark.GcRoot
import shark.GcRoot.JavaFrame
import shark.GcRoot.ThreadObject
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.IgnoredReferenceMatcher
import shark.LeakReference
import shark.LeakTraceElement.Type.ARRAY_ENTRY
import shark.LeakTraceElement.Type.INSTANCE_FIELD
import shark.LeakTraceElement.Type.LOCAL
import shark.LeakTraceElement.Type.STATIC_FIELD
import shark.LibraryLeakReferenceMatcher
import shark.OnAnalysisProgressListener
import shark.OnAnalysisProgressListener.Step.FINDING_DOMINATORS
import shark.OnAnalysisProgressListener.Step.FINDING_PATHS_TO_LEAKING_INSTANCES
import shark.PrimitiveType.INT
import shark.ReferenceMatcher
import shark.ReferencePattern
import shark.ReferencePattern.InstanceFieldPattern
import shark.ReferencePattern.StaticFieldPattern
import shark.SharkLog
import shark.ValueHolder
import shark.internal.ReferencePathNode.ChildNode.LibraryLeakNode
import shark.internal.ReferencePathNode.ChildNode.NormalNode
import shark.internal.ReferencePathNode.RootNode
import shark.internal.hppc.LongLongScatterMap
import shark.internal.hppc.LongScatterSet
import java.util.ArrayDeque
import java.util.Deque
import java.util.LinkedHashMap

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
  referenceMatchers: List<ReferenceMatcher>
) {

  private val fieldNameByClassName: Map<String, Map<String, ReferenceMatcher>>
  private val staticFieldNameByClassName: Map<String, Map<String, ReferenceMatcher>>
  private val threadNameReferenceMatchers: Map<String, ReferenceMatcher>

  init {
    val fieldNameByClassName = mutableMapOf<String, MutableMap<String, ReferenceMatcher>>()
    val staticFieldNameByClassName = mutableMapOf<String, MutableMap<String, ReferenceMatcher>>()
    val threadNames = mutableMapOf<String, ReferenceMatcher>()

    referenceMatchers.filter {
      (it is IgnoredReferenceMatcher || (it is LibraryLeakReferenceMatcher && it.patternApplies(
          graph
      )))
    }
        .forEach { referenceMatcher ->
          when (val pattern = referenceMatcher.pattern) {
            is ReferencePattern.JavaLocalPattern -> {
              threadNames[pattern.threadName] = referenceMatcher
            }
            is StaticFieldPattern -> {
              val mapOrNull = staticFieldNameByClassName[pattern.className]
              val map = if (mapOrNull != null) mapOrNull else {
                val newMap = mutableMapOf<String, ReferenceMatcher>()
                staticFieldNameByClassName[pattern.className] = newMap
                newMap
              }
              map[pattern.fieldName] = referenceMatcher
            }
            is InstanceFieldPattern -> {
              val mapOrNull = fieldNameByClassName[pattern.className]
              val map = if (mapOrNull != null) mapOrNull else {
                val newMap = mutableMapOf<String, ReferenceMatcher>()
                fieldNameByClassName[pattern.className] = newMap
                newMap
              }
              map[pattern.fieldName] = referenceMatcher
            }
          }
        }
    this.fieldNameByClassName = fieldNameByClassName
    this.staticFieldNameByClassName = staticFieldNameByClassName
    this.threadNameReferenceMatchers = threadNames
  }

  private class State(
    val leakingInstanceObjectIds: Set<Long>,
    val sizeOfObjectInstances: Int,
    val computeRetainedHeapSize: Boolean
  ) {

    /** Set of instances to visit */
    val toVisitQueue: Deque<ReferencePathNode> = ArrayDeque()

    /**
     * Instances to visit when [toVisitQueue] is empty. Should contain [JavaFrame] gc roots first,
     * then [LibraryLeakNode].
     */
    val toVisitLastQueue: Deque<ReferencePathNode> = ArrayDeque()
    /**
     * Enables fast checking of whether a node is already in the queue.
     */
    val toVisitSet = HashSet<Long>()
    val toVisitLastSet = HashSet<Long>()

    val visitedSet = LongScatterSet()

    /**
     * Map of instances to their leaking dominator.
     * If an instance has been added to [toVisitSet] or [visitedSet] and is missing from
     * [dominatedInstances] then it's considered "undomitable" ie it is dominated by gc roots
     * and cannot be dominated by a leaking instance.
     */
    val dominatedInstances = LongLongScatterMap()

    val queuesNotEmpty: Boolean
      get() = toVisitQueue.isNotEmpty() || toVisitLastQueue.isNotEmpty()
  }

  class PathFindingResults(
    val pathsToLeakingInstances: List<ReferencePathNode>,
    val dominatedInstances: LongLongScatterMap
  )

  fun findPathsFromGcRoots(
    leakingInstanceObjectIds: Set<Long>,
    computeRetainedHeapSize: Boolean
  ): PathFindingResults {
    listener.onAnalysisProgress(FINDING_PATHS_TO_LEAKING_INSTANCES)

    val sizeOfObjectInstances = determineSizeOfObjectInstances(graph)

    val state = State(leakingInstanceObjectIds, sizeOfObjectInstances, computeRetainedHeapSize)

    return state.findPathsFromGcRoots(leakingInstanceObjectIds)
  }

  private fun determineSizeOfObjectInstances(graph: HeapGraph): Int {
    val objectClass = graph.findClassByName("java.lang.Object")
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

  private fun State.findPathsFromGcRoots(leakingInstanceObjectIds: Set<Long>): PathFindingResults {
    enqueueGcRoots()

    val shortestPathsToLeakingInstances = mutableListOf<ReferencePathNode>()
    visitingQueue@ while (queuesNotEmpty) {
      val node = poll()

      if (checkSeen(node)) {
        throw IllegalStateException(
            "Node $node objectId=${node.instance} should not be enqueued when already visited or enqueued"
        )
      }

      if (node.instance in leakingInstanceObjectIds) {
        shortestPathsToLeakingInstances.add(node)
        // Found all refs, stop searching (unless computing retained size)
        if (shortestPathsToLeakingInstances.size == leakingInstanceObjectIds.size) {
          if (computeRetainedHeapSize) {
            listener.onAnalysisProgress(FINDING_DOMINATORS)
          } else {
            break@visitingQueue
          }
        }
      }

      when (val heapObject = graph.findObjectById(node.instance)) {
        is HeapClass -> visitClassRecord(heapObject, node)
        is HeapInstance -> visitInstanceRecord(heapObject, node)
        is HeapObjectArray -> visitObjectArrayRecord(heapObject, node)
      }
    }
    return PathFindingResults(shortestPathsToLeakingInstances, dominatedInstances)
  }

  private fun State.poll(): ReferencePathNode {
    return if (!toVisitQueue.isEmpty()) {
      val removedNode = toVisitQueue.poll()
      toVisitSet.remove(removedNode.instance)
      removedNode
    } else {
      val removedNode = toVisitLastQueue.poll()
      toVisitLastSet.remove(removedNode.instance)
      removedNode
    }
  }

  private fun State.checkSeen(node: ReferencePathNode): Boolean {
    val neverSeen = visitedSet.add(node.instance)
    return !neverSeen
  }

  private fun State.enqueueGcRoots() {
    val gcRoots = sortedGcRoots()

    val threadsBySerialNumber = mutableMapOf<Int, Pair<HeapInstance, ThreadObject>>()
    gcRoots.forEach { (objectRecord, gcRoot) ->
      if (computeRetainedHeapSize) {
        undominateWithSkips(gcRoot.id)
      }
      when (gcRoot) {
        is ThreadObject -> {
          threadsBySerialNumber[gcRoot.threadSerialNumber] = objectRecord.asInstance!! to gcRoot
          enqueue(RootNode(gcRoot, gcRoot.id))
        }
        is JavaFrame -> {
          val (threadInstance, threadRoot) = threadsBySerialNumber.getValue(
              gcRoot.threadSerialNumber
          )
          val threadName = threadInstance[Thread::class, "name"]?.value?.readAsJavaString()
          val referenceMatcher = threadNameReferenceMatchers[threadName]

          if (referenceMatcher !is IgnoredReferenceMatcher) {
            val rootNode = RootNode(gcRoot, threadRoot.id)
            // Unfortunately Android heap dumps do not include stack trace data, so
            // JavaFrame.frameNumber is always -1 and we cannot know which method is causing the
            // reference to be held.
            val leakReference = LeakReference(LOCAL, "")

            val childNode = if (referenceMatcher is LibraryLeakReferenceMatcher) {
              LibraryLeakNode(gcRoot.id, rootNode, leakReference, referenceMatcher)
            } else {
              NormalNode(gcRoot.id, rootNode, leakReference)
            }
            enqueue(childNode)
          }
        }
        else -> enqueue(RootNode(gcRoot, gcRoot.id))
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

    return graph.gcRoots
        .map { graph.findObjectById(it.id) to it }
        .sortedWith(Comparator { (graphObject1, root1), (graphObject2, root2) ->
          // Sorting based on pattern name first. In reverse order so that ThreadObject is before JavaLocalPattern
          val gcRootTypeComparison = root2::class.java.name.compareTo(root1::class.java.name)
          if (gcRootTypeComparison != 0) {
            gcRootTypeComparison
          } else {
            rootClassName(graphObject1).compareTo(rootClassName(graphObject2))
          }
        })
  }

  private fun State.visitClassRecord(
    heapClass: HeapClass,
    parent: ReferencePathNode
  ) {
    val ignoredStaticFields = staticFieldNameByClassName[heapClass.name] ?: emptyMap()

    for (staticField in heapClass.readStaticFields()) {
      if (!staticField.value.isNonNullReference) {
        continue
      }

      val fieldName = staticField.name
      if (fieldName == "\$staticOverhead") {
        continue
      }

      val objectId = staticField.value.asObjectId!!

      if (computeRetainedHeapSize) {
        undominateWithSkips(objectId)
      }

      val node = when (val referenceMatcher = ignoredStaticFields[fieldName]) {
        null -> NormalNode(objectId, parent, LeakReference(STATIC_FIELD, fieldName))
        is LibraryLeakReferenceMatcher -> LibraryLeakNode(
            objectId, parent, LeakReference(STATIC_FIELD, fieldName), referenceMatcher
        )
        is IgnoredReferenceMatcher -> null
      }
      if (node != null) {
        enqueue(node)
      }
    }
  }

  private fun State.visitInstanceRecord(
    instance: HeapInstance,
    parent: ReferencePathNode
  ) {
    val fieldReferenceMatchers = LinkedHashMap<String, ReferenceMatcher>()

    instance.instanceClass.classHierarchy.forEach {
      val referenceMatcherByField = fieldNameByClassName[it.name]
      if (referenceMatcherByField != null) {
        for ((fieldName, referenceMatcher) in referenceMatcherByField) {
          if (!fieldReferenceMatchers.containsKey(fieldName)) {
            fieldReferenceMatchers[fieldName] = referenceMatcher
          }
        }
      }
    }

    val fieldNamesAndValues = instance.readFields()
        .filter { it.value.isNonNullReference }
        .toMutableList()

    fieldNamesAndValues.sortBy { it.name }

    fieldNamesAndValues.forEach { field ->
      val objectId = field.value.asObjectId!!
      if (computeRetainedHeapSize) {
        updateDominatorWithSkips(parent.instance, objectId)
      }
      val node = when (val referenceMatcher = fieldReferenceMatchers[field.name]) {
        null -> NormalNode(objectId, parent, LeakReference(INSTANCE_FIELD, field.name))
        is LibraryLeakReferenceMatcher ->
          LibraryLeakNode(
              objectId, parent, LeakReference(INSTANCE_FIELD, field.name), referenceMatcher
          )
        is IgnoredReferenceMatcher -> null
      }
      if (node != null) {
        enqueue(node)
      }
    }
  }

  private fun State.visitObjectArrayRecord(
    objectArray: HeapObjectArray,
    parent: ReferencePathNode
  ) {
    val record = objectArray.readRecord()
    val nonNullElementIds = record.elementIds.filter { objectId ->
      objectId != ValueHolder.NULL_REFERENCE && graph.objectExists(objectId).apply {
        if (!this) {
          // dalvik.system.PathClassLoader.runtimeInternalObjects references objects which don't
          // otherwise exist in the heap dump.
          SharkLog.d("Invalid Hprof? Found unknown object id $objectId")
        }
      }
    }
    nonNullElementIds.forEachIndexed { index, elementId ->
      if (computeRetainedHeapSize) {
        updateDominatorWithSkips(parent.instance, elementId)
      }
      val name = Integer.toString(index)
      enqueue(NormalNode(elementId, parent, LeakReference(ARRAY_ENTRY, name)))
    }
  }

  private fun State.enqueue(
    node: ReferencePathNode
  ) {
    if (node.instance == ValueHolder.NULL_REFERENCE) {
      return
    }
    if (visitedSet.contains(node.instance)) {
      return
    }
    // Already enqueued => shorter or equal distance
    if (toVisitSet.contains(node.instance)) {
      return
    }

    val visitLast =
      node is LibraryLeakNode ||
          (node is NormalNode && node.parent is RootNode && node.parent.gcRoot is JavaFrame)

    if (toVisitLastSet.contains(node.instance)) {
      // Already enqueued => shorter or equal distance amongst library leak ref patterns.
      if (visitLast) {
        return
      } else {
        toVisitQueue.add(node)
        toVisitSet.add(node.instance)
        val nodeToRemove = toVisitLastQueue.first { it.instance == node.instance }
        toVisitLastQueue.remove(nodeToRemove)
        toVisitLastSet.remove(node.instance)
        return
      }
    }

    val isLeakingInstance = node.instance in leakingInstanceObjectIds

    if (!isLeakingInstance) {
      val skip = when (val graphObject = graph.findObjectById(node.instance)) {
        is HeapClass -> false
        is HeapInstance ->
          when {
            graphObject.isPrimitiveWrapper -> true
            graphObject.instanceClassName == "java.lang.String" -> true
            graphObject.instanceClass.instanceByteSize <= sizeOfObjectInstances -> true
            else -> false
          }
        is HeapObjectArray -> when {
          graphObject.isPrimitiveWrapperArray -> true
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
      toVisitLastSet.add(node.instance)
    } else {
      toVisitQueue.add(node)
      toVisitSet.add(node.instance)
    }
  }

  private fun State.updateDominatorWithSkips(
    parentObjectId: Long,
    objectId: Long
  ) {

    when (val graphObject = graph.findObjectById(objectId)) {
      is HeapClass -> {
        undominate(objectId, false)
      }
      is HeapInstance -> {
        // String internal array is never enqueued
        if (graphObject.instanceClassName == "java.lang.String") {
          updateDominator(parentObjectId, objectId, true)
          val valueId = graphObject["java.lang.String", "value"]?.value?.asObjectId
          if (valueId != null) {
            updateDominator(parentObjectId, valueId, true)
          }
        } else {
          updateDominator(parentObjectId, objectId, false)
        }
      }
      is HeapObjectArray -> {
        // Primitive wrapper array elements are never enqueued
        if (graphObject.isPrimitiveWrapperArray) {
          updateDominator(parentObjectId, objectId, true)
          for (wrapperId in graphObject.readRecord().elementIds) {
            updateDominator(parentObjectId, wrapperId, true)
          }
        } else {
          updateDominator(parentObjectId, objectId, false)
        }
      }
      else -> {
        updateDominator(parentObjectId, objectId, false)
      }
    }
  }

  private fun State.updateDominator(
    parent: Long,
    instance: Long,
    neverEnqueued: Boolean
  ) {
    val currentDominator = dominatedInstances[instance]
    if (currentDominator == null && (instance in visitedSet || instance in toVisitSet || instance in toVisitLastSet)) {
      return
    }
    val parentDominator = dominatedInstances[parent]

    val parentIsRetainedInstance = parent in leakingInstanceObjectIds

    val nextDominator = if (parentIsRetainedInstance) parent else parentDominator

    if (nextDominator == null) {
      // parent is not a retained instance and parent has no dominator, but it must have been
      // visited therefore we know parent belongs to undominated.
      if (neverEnqueued) {
        visitedSet.add(instance)
      }

      if (currentDominator != null) {
        dominatedInstances.remove(instance)
      }
      return
    }
    if (currentDominator == null) {
      dominatedInstances[instance] = nextDominator
    } else {
      val parentDominators = mutableListOf<Long>()
      val currentDominators = mutableListOf<Long>()
      var dominator: Long? = nextDominator
      while (dominator != null) {
        parentDominators.add(dominator)
        dominator = dominatedInstances[dominator]
      }
      dominator = currentDominator
      while (dominator != null) {
        currentDominators.add(dominator)
        dominator = dominatedInstances[dominator]
      }

      var sharedDominator: Long? = null
      exit@ for (parentD in parentDominators) {
        for (currentD in currentDominators) {
          if (currentD == parentD) {
            sharedDominator = currentD
            break@exit
          }
        }
      }
      if (sharedDominator == null) {
        dominatedInstances.remove(instance)
        if (neverEnqueued) {
          visitedSet.add(instance)
        }
      } else {
        dominatedInstances[instance] = sharedDominator
      }
    }
  }

  private fun State.undominateWithSkips(objectId: Long) {
    when (val graphObject = graph.findObjectById(objectId)) {
      is HeapClass -> {
        undominate(objectId, false)
      }
      is HeapInstance -> {
        // String internal array is never enqueued
        if (graphObject.instanceClassName == "java.lang.String") {
          undominate(objectId, true)
          val valueId = graphObject["java.lang.String", "value"]?.value?.asObjectId
          if (valueId != null) {
            undominate(valueId, true)
          }
        } else {
          undominate(objectId, false)
        }
      }
      is HeapObjectArray -> {
        // Primitive wrapper array elements are never enqueued
        if (graphObject.isPrimitiveWrapperArray) {
          undominate(objectId, true)
          for (wrapperId in graphObject.readRecord().elementIds) {
            undominate(wrapperId, true)
          }
        } else {
          undominate(objectId, false)
        }
      }
      else -> {
        undominate(objectId, false)
      }
    }
  }

  private fun State.undominate(
    instance: Long,
    neverEnqueued: Boolean
  ) {
    dominatedInstances.remove(instance)
    if (neverEnqueued) {
      visitedSet.add(instance)
    }
  }
}
