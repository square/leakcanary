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
import shark.GcRoot.JniGlobal
import shark.GcRoot.ThreadObject
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.IgnoredReferenceMatcher
import shark.LeakTraceReference.ReferenceType.ARRAY_ENTRY
import shark.LeakTraceReference.ReferenceType.INSTANCE_FIELD
import shark.LeakTraceReference.ReferenceType.LOCAL
import shark.LeakTraceReference.ReferenceType.STATIC_FIELD
import shark.LibraryLeakReferenceMatcher
import shark.OnAnalysisProgressListener
import shark.OnAnalysisProgressListener.Step.FINDING_DOMINATORS
import shark.OnAnalysisProgressListener.Step.FINDING_PATHS_TO_RETAINED_OBJECTS
import shark.PrimitiveType.INT
import shark.ReferenceMatcher
import shark.ReferencePattern
import shark.ReferencePattern.InstanceFieldPattern
import shark.ReferencePattern.NativeGlobalVariablePattern
import shark.ReferencePattern.StaticFieldPattern
import shark.ValueHolder
import shark.internal.ReferencePathNode.ChildNode.LibraryLeakChildNode
import shark.internal.ReferencePathNode.ChildNode.NormalNode
import shark.internal.ReferencePathNode.LibraryLeakNode
import shark.internal.ReferencePathNode.RootNode
import shark.internal.ReferencePathNode.RootNode.LibraryLeakRootNode
import shark.internal.ReferencePathNode.RootNode.NormalRootNode
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
@Suppress("TooManyFunctions")
internal class PathFinder(
  private val graph: HeapGraph,
  private val listener: OnAnalysisProgressListener,
  referenceMatchers: List<ReferenceMatcher>
) {

  class PathFindingResults(
    val pathsToLeakingObjects: List<ReferencePathNode>,
    val dominatedObjectIds: LongLongScatterMap
  )

  private class State(
    val leakingObjectIds: Set<Long>,
    val sizeOfObjectInstances: Int,
    val computeRetainedHeapSize: Boolean
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
    val toVisitSet = HashSet<Long>()
    val toVisitLastSet = HashSet<Long>()

    val visitedSet = LongScatterSet()

    /**
     * Map of objects to their leaking dominator.
     * If an object has been added to [toVisitSet] or [visitedSet] and is missing from
     * [dominatedObjectIds] then it's considered "undomitable" ie it is dominated by gc roots
     * and cannot be dominated by a leaking object.
     */
    val dominatedObjectIds = LongLongScatterMap()

    val queuesNotEmpty: Boolean
      get() = toVisitQueue.isNotEmpty() || toVisitLastQueue.isNotEmpty()
  }

  private val fieldNameByClassName: Map<String, Map<String, ReferenceMatcher>>
  private val staticFieldNameByClassName: Map<String, Map<String, ReferenceMatcher>>
  private val threadNameReferenceMatchers: Map<String, ReferenceMatcher>
  private val jniGlobalReferenceMatchers: Map<String, ReferenceMatcher>

  init {
    val fieldNameByClassName = mutableMapOf<String, MutableMap<String, ReferenceMatcher>>()
    val staticFieldNameByClassName = mutableMapOf<String, MutableMap<String, ReferenceMatcher>>()
    val threadNames = mutableMapOf<String, ReferenceMatcher>()
    val jniGlobals = mutableMapOf<String, ReferenceMatcher>()

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
            is NativeGlobalVariablePattern -> {
              jniGlobals[pattern.className] = referenceMatcher
            }
          }
        }
    this.fieldNameByClassName = fieldNameByClassName
    this.staticFieldNameByClassName = staticFieldNameByClassName
    this.threadNameReferenceMatchers = threadNames
    this.jniGlobalReferenceMatchers = jniGlobals
  }

  fun findPathsFromGcRoots(
    leakingObjectIds: Set<Long>,
    computeRetainedHeapSize: Boolean
  ): PathFindingResults {
    listener.onAnalysisProgress(FINDING_PATHS_TO_RETAINED_OBJECTS)

    val sizeOfObjectInstances = determineSizeOfObjectInstances(graph)

    val state = State(leakingObjectIds, sizeOfObjectInstances, computeRetainedHeapSize)

    return state.findPathsFromGcRoots()
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

  private fun State.findPathsFromGcRoots(): PathFindingResults {
    enqueueGcRoots()

    val shortestPathsToLeakingObjects = mutableListOf<ReferencePathNode>()
    visitingQueue@ while (queuesNotEmpty) {
      val node = poll()

      if (checkSeen(node)) {
        throw IllegalStateException(
            "Node $node objectId=${node.objectId} should not be enqueued when already visited or enqueued"
        )
      }

      if (node.objectId in leakingObjectIds) {
        shortestPathsToLeakingObjects.add(node)
        // Found all refs, stop searching (unless computing retained size)
        if (shortestPathsToLeakingObjects.size == leakingObjectIds.size) {
          if (computeRetainedHeapSize) {
            listener.onAnalysisProgress(FINDING_DOMINATORS)
          } else {
            break@visitingQueue
          }
        }
      }

      when (val heapObject = graph.findObjectById(node.objectId)) {
        is HeapClass -> visitClassRecord(heapObject, node)
        is HeapInstance -> visitInstance(heapObject, node)
        is HeapObjectArray -> visitObjectArray(heapObject, node)
      }
    }
    return PathFindingResults(shortestPathsToLeakingObjects, dominatedObjectIds)
  }

  private fun State.poll(): ReferencePathNode {
    return if (!toVisitQueue.isEmpty()) {
      val removedNode = toVisitQueue.poll()
      toVisitSet.remove(removedNode.objectId)
      removedNode
    } else {
      val removedNode = toVisitLastQueue.poll()
      toVisitLastSet.remove(removedNode.objectId)
      removedNode
    }
  }

  private fun State.checkSeen(node: ReferencePathNode): Boolean {
    val neverSeen = visitedSet.add(node.objectId)
    return !neverSeen
  }

  private fun State.enqueueGcRoots() {
    val gcRoots = sortedGcRoots()

    val threadNames = mutableMapOf<HeapInstance, String>()
    val threadsBySerialNumber = mutableMapOf<Int, Pair<HeapInstance, ThreadObject>>()
    gcRoots.forEach { (objectRecord, gcRoot) ->
      if (computeRetainedHeapSize) {
        undominateWithSkips(gcRoot.id)
      }
      when (gcRoot) {
        is ThreadObject -> {
          threadsBySerialNumber[gcRoot.threadSerialNumber] = objectRecord.asInstance!! to gcRoot
          enqueue(NormalRootNode(gcRoot.id, gcRoot))
        }
        is JavaFrame -> {
          val threadPair = threadsBySerialNumber[gcRoot.threadSerialNumber]
          if (threadPair == null) {
            // Could not find the thread that this java frame is for.
            enqueue(NormalRootNode(gcRoot.id, gcRoot))
          } else {

            val (threadInstance, threadRoot) = threadPair
            val threadName = threadNames[threadInstance] ?: {
              val name = threadInstance[Thread::class, "name"]?.value?.readAsJavaString() ?: ""
              threadNames[threadInstance] = name
              name
            }()
            val referenceMatcher = threadNameReferenceMatchers[threadName]

            if (referenceMatcher !is IgnoredReferenceMatcher) {
              val rootNode = NormalRootNode(threadRoot.id, gcRoot)

              val refFromParentType = LOCAL
              // Unfortunately Android heap dumps do not include stack trace data, so
              // JavaFrame.frameNumber is always -1 and we cannot know which method is causing the
              // reference to be held.
              val refFromParentName = ""

              val childNode = if (referenceMatcher is LibraryLeakReferenceMatcher) {
                LibraryLeakChildNode(
                    objectId = gcRoot.id,
                    parent = rootNode,
                    refFromParentType = refFromParentType,
                    refFromParentName = refFromParentName,
                    matcher = referenceMatcher
                )
              } else {
                NormalNode(
                    objectId = gcRoot.id,
                    parent = rootNode,
                    refFromParentType = refFromParentType,
                    refFromParentName = refFromParentName
                )
              }
              enqueue(childNode)
            }
          }
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
              enqueue(LibraryLeakRootNode(gcRoot.id, gcRoot, referenceMatcher))
            } else {
              enqueue(NormalRootNode(gcRoot.id, gcRoot))
            }
          }
        }
        else -> enqueue(NormalRootNode(gcRoot.id, gcRoot))
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
        .filter { gcRoot ->
          // GC roots sometimes reference objects that don't exist in the heap dump
          // See https://github.com/square/leakcanary/issues/1516
          graph.objectExists(gcRoot.id)
        }
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
        null -> NormalNode(
            objectId = objectId,
            parent = parent,
            refFromParentType = STATIC_FIELD,
            refFromParentName = fieldName
        )
        is LibraryLeakReferenceMatcher -> LibraryLeakChildNode(
            objectId = objectId,
            parent = parent,
            refFromParentType = STATIC_FIELD,
            refFromParentName = fieldName,
            matcher = referenceMatcher
        )
        is IgnoredReferenceMatcher -> null
      }
      if (node != null) {
        enqueue(node)
      }
    }
  }

  private fun State.visitInstance(
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
        updateDominatorWithSkips(parent.objectId, objectId)
      }

      val node = when (val referenceMatcher = fieldReferenceMatchers[field.name]) {
        null -> NormalNode(
            objectId = objectId,
            parent = parent,
            refFromParentType = INSTANCE_FIELD,
            refFromParentName = field.name
        )
        is LibraryLeakReferenceMatcher ->
          LibraryLeakChildNode(
              objectId = objectId,
              parent = parent,
              refFromParentType = INSTANCE_FIELD,
              refFromParentName = field.name,
              matcher = referenceMatcher
          )
        is IgnoredReferenceMatcher -> null
      }
      if (node != null) {
        enqueue(node)
      }
    }
  }

  private fun State.visitObjectArray(
    objectArray: HeapObjectArray,
    parent: ReferencePathNode
  ) {
    val record = objectArray.readRecord()
    val nonNullElementIds = record.elementIds.filter { objectId ->
      objectId != ValueHolder.NULL_REFERENCE && graph.objectExists(objectId)
    }
    nonNullElementIds.forEachIndexed { index, elementId ->
      if (computeRetainedHeapSize) {
        updateDominatorWithSkips(parent.objectId, elementId)
      }
      val name = index.toString()
      enqueue(
          NormalNode(
              objectId = elementId,
              parent = parent,
              refFromParentType = ARRAY_ENTRY,
              refFromParentName = name
          )
      )
    }
  }

  @Suppress("ReturnCount")
  private fun State.enqueue(
    node: ReferencePathNode
  ) {
    if (node.objectId == ValueHolder.NULL_REFERENCE) {
      return
    }
    if (visitedSet.contains(node.objectId)) {
      return
    }
    // Already enqueued => shorter or equal distance
    if (toVisitSet.contains(node.objectId)) {
      return
    }

    val visitLast =
      node is LibraryLeakNode ||
          // We deprioritize thread objects because on Lollipop the thread local values are stored
          // as a field.
          (node is RootNode && node.gcRoot is ThreadObject) ||
          (node is NormalNode && node.parent is RootNode && node.parent.gcRoot is JavaFrame)

    if (toVisitLastSet.contains(node.objectId)) {
      // Already enqueued => shorter or equal distance amongst library leak ref patterns.
      if (visitLast) {
        return
      } else {
        toVisitQueue.add(node)
        toVisitSet.add(node.objectId)
        val nodeToRemove = toVisitLastQueue.first { it.objectId == node.objectId }
        toVisitLastQueue.remove(nodeToRemove)
        toVisitLastSet.remove(node.objectId)
        return
      }
    }

    val isLeakingObject = node.objectId in leakingObjectIds

    if (!isLeakingObject) {
      val skip = when (val graphObject = graph.findObjectById(node.objectId)) {
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
      toVisitLastSet.add(node.objectId)
    } else {
      toVisitQueue.add(node)
      toVisitSet.add(node.objectId)
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

  @Suppress("ComplexCondition")
  private fun State.updateDominator(
    parent: Long,
    objectId: Long,
    neverEnqueued: Boolean
  ) {
    val currentDominatorSlot = dominatedObjectIds.getSlot(objectId)
    if (currentDominatorSlot == -1 && (objectId in visitedSet || objectId in toVisitSet || objectId in toVisitLastSet)) {
      return
    }
    val parentDominatorSlot = dominatedObjectIds.getSlot(parent)

    val parentIsRetainedObject = parent in leakingObjectIds

    if (!parentIsRetainedObject && parentDominatorSlot == -1) {
      // parent is not a retained instance and parent has no dominator, but it must have been
      // visited therefore we know parent belongs to undominated.
      if (neverEnqueued) {
        visitedSet.add(objectId)
      }

      if (currentDominatorSlot != -1) {
        dominatedObjectIds.remove(objectId)
      }
      return
    }
    val nextDominator =
      if (parentIsRetainedObject) parent else dominatedObjectIds.getSlotValue(parentDominatorSlot)
    if (currentDominatorSlot == -1) {
      dominatedObjectIds[objectId] = nextDominator
    } else {
      val parentDominators = mutableListOf<Long>()
      val currentDominators = mutableListOf<Long>()
      var stop = false
      var dominator: Long = nextDominator
      while (!stop) {
        parentDominators.add(dominator)
        val nextDominatorSlot = dominatedObjectIds.getSlot(dominator)
        if (nextDominatorSlot == -1) {
          stop = true
        } else {
          dominator = dominatedObjectIds.getSlotValue(nextDominatorSlot)
        }
      }
      stop = false
      dominator = dominatedObjectIds.getSlotValue(currentDominatorSlot)
      while (!stop) {
        currentDominators.add(dominator)
        val nextDominatorSlot = dominatedObjectIds.getSlot(dominator)
        if (nextDominatorSlot == -1) {
          stop = true
        } else {
          dominator = dominatedObjectIds.getSlotValue(nextDominatorSlot)
        }
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
        dominatedObjectIds.remove(objectId)
        if (neverEnqueued) {
          visitedSet.add(objectId)
        }
      } else {
        dominatedObjectIds[objectId] = sharedDominator
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
    objectId: Long,
    neverEnqueued: Boolean
  ) {
    dominatedObjectIds.remove(objectId)
    if (neverEnqueued) {
      visitedSet.add(objectId)
    }
  }
}
