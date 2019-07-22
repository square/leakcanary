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

import shark.AnalyzerProgressListener.Step.FINDING_DOMINATORS
import shark.AnalyzerProgressListener.Step.FINDING_PATHS_TO_LEAKING_INSTANCES
import shark.GcRoot
import shark.GcRoot.JavaFrame
import shark.GcRoot.ThreadObject
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import shark.LeakReference
import shark.LeakTraceElement.Type.ARRAY_ENTRY
import shark.LeakTraceElement.Type.INSTANCE_FIELD
import shark.LeakTraceElement.Type.LOCAL
import shark.LeakTraceElement.Type.STATIC_FIELD
import shark.PrimitiveType
import shark.ReferenceMatcher
import shark.ReferenceMatcher.IgnoredReferenceMatcher
import shark.ReferenceMatcher.LibraryLeakReferenceMatcher
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
internal class ShortestPathFinder {

  /** Set of instances to visit */
  private val toVisitQueue: Deque<ReferencePathNode> = ArrayDeque()
  private val toVisitLastQueue: Deque<LibraryLeakNode> = ArrayDeque()
  /**
   * Enables fast checking of whether a node is already in the queue.
   */
  private val toVisitSet = HashSet<Long>()
  private val toVisitLastSet = HashSet<Long>()

  private val visitedSet = LongScatterSet()
  private lateinit var leakingInstanceObjectIds: Set<Long>

  /**
   * Map of instances to their leaking dominator.
   * var because the instance will be returned by [findPaths] and replaced with a new empty map
   * here (copying it could be expensive).
   *
   * If an instance has been added to [toVisitSet] or [visitedSet] and is missing from
   * [dominatedInstances] then it's considered "undomitable" ie it is dominated by gc roots
   * and cannot be dominated by a leaking instance.
   */
  private var dominatedInstances = LongLongScatterMap()
  private var sizeOfObjectInstances = 0

  data class Results(
    val shortestPathsToLeakingInstances: List<ReferencePathNode>,
    val dominatedInstances: LongLongScatterMap
  )

  fun findPaths(
    graph: HeapGraph,
    referenceMatchers: List<ReferenceMatcher>,
    leakingInstanceObjectIds: Set<Long>,
    computeDominators: Boolean,
    listener: shark.AnalyzerProgressListener
  ): Results {

    listener.onProgressUpdate(FINDING_PATHS_TO_LEAKING_INSTANCES)
    clearState()
    this.leakingInstanceObjectIds = leakingInstanceObjectIds

    val objectClass = graph.findClassByName("java.lang.Object")
    sizeOfObjectInstances = if (objectClass != null) {
      // In Android 16 ClassDumpRecord.instanceSize can be 8 yet there are 0 fields.
      // Better rely on our own computation of instance size.
      // See #1374
      val objectClassFieldSize = objectClass.readFieldsSize()

      // shadow$_klass_ (object id) + shadow$_monitor_ (Int)
      val sizeOfObjectOnArt = graph.objectIdByteSize + PrimitiveType.INT.byteSize
      if (objectClassFieldSize == sizeOfObjectOnArt) {
        sizeOfObjectOnArt
      } else {
        0
      }
    } else {
      0
    }

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

    enqueueGcRoots(graph, threadNames, computeDominators)

    val shortestPathsToLeakingInstances = mutableListOf<ReferencePathNode>()
    visitingQueue@ while (!toVisitQueue.isEmpty() || !toVisitLastQueue.isEmpty()) {
      val node = if (!toVisitQueue.isEmpty()) {
        val removedNode = toVisitQueue.poll()
        toVisitSet.remove(removedNode.instance)
        removedNode
      } else {
        val removedNode = toVisitLastQueue.poll()
        toVisitLastSet.remove(removedNode.instance)
        removedNode
      }

      if (checkSeen(node)) {
        throw IllegalStateException(
            "Node $node objectId=${node.instance} should not be enqueued when already visited or enqueued"
        )
      }

      if (node.instance in leakingInstanceObjectIds) {
        shortestPathsToLeakingInstances.add(node)
        // Found all refs, stop searching (unless computing retained size which stops on weak reachables)
        if (shortestPathsToLeakingInstances.size == leakingInstanceObjectIds.size) {
          if (computeDominators) {
            listener.onProgressUpdate(FINDING_DOMINATORS)
          } else {
            break@visitingQueue
          }
        }
      }

      when (val graphRecord = graph.findObjectById(node.instance)) {
        is HeapClass -> visitClassRecord(
            graph, graphRecord, node, staticFieldNameByClassName, computeDominators
        )
        is HeapInstance -> visitInstanceRecord(
            graph, graphRecord, node, fieldNameByClassName, computeDominators
        )
        is HeapObjectArray -> visitObjectArrayRecord(
            graph, graphRecord.readRecord(), node, computeDominators
        )
      }
    }

    val dominatedInstances = this.dominatedInstances

    clearState()

    return Results(shortestPathsToLeakingInstances, dominatedInstances)
  }

  private fun checkSeen(node: ReferencePathNode): Boolean {
    val neverSeen = visitedSet.add(node.instance)
    return !neverSeen
  }

  private fun clearState() {
    toVisitQueue.clear()
    toVisitLastQueue.clear()
    toVisitSet.clear()
    toVisitLastSet.clear()
    visitedSet.release()
    dominatedInstances = LongLongScatterMap()
    leakingInstanceObjectIds = emptySet()
    sizeOfObjectInstances = 0
  }

  private fun enqueueGcRoots(
    graph: HeapGraph,
    threadNameReferenceMatchers: Map<String, ReferenceMatcher>,
    computeDominators: Boolean
  ) {
    val gcRoots = sortedGcRoots(graph)

    val threadsBySerialNumber = mutableMapOf<Int, Pair<HeapInstance, ThreadObject>>()
    gcRoots.forEach { (objectRecord, gcRoot) ->
      if (computeDominators) {
        undominateWithSkips(graph, gcRoot.id)
      }
      when (gcRoot) {
        is ThreadObject -> {
          threadsBySerialNumber[gcRoot.threadSerialNumber] = objectRecord.asInstance!! to gcRoot
          enqueue(graph, RootNode(gcRoot, gcRoot.id))
        }
        is JavaFrame -> {
          val (threadInstance, threadRoot) = threadsBySerialNumber.getValue(
              gcRoot.threadSerialNumber
          )
          val threadName = threadInstance[Thread::class, "name"]?.value?.readAsJavaString()
          val referenceMatcher = threadNameReferenceMatchers[threadName]

          if (referenceMatcher !is IgnoredReferenceMatcher) {
            // visitOrder is unused as this root node isn't enqueued.
            val rootNode = RootNode(gcRoot, threadRoot.id)
            // TODO #1352 Instead of <Java Local>, it should be <local variable in Foo.bar()>
            // We should also add the full stacktrace as a label of thread objects
            val leakReference = LeakReference(LOCAL, "")

            val childNode = if (referenceMatcher is LibraryLeakReferenceMatcher) {
              LibraryLeakNode(gcRoot.id, rootNode, leakReference, referenceMatcher)
            } else {
              NormalNode(gcRoot.id, rootNode, leakReference)
            }
            enqueue(graph, childNode)
          }
        }
        else -> enqueue(graph, RootNode(gcRoot, gcRoot.id))
      }
    }
  }

  /**
   * Sorting GC roots to get stable shortest path
   * Once sorted all ThreadObject Gc Roots are located before JavaLocalPattern Gc Roots.
   * This ensures ThreadObjects are visited before JavaFrames, and threadsBySerialNumber can be
   * built before JavaFrames.
   */
  private fun sortedGcRoots(
    graph: HeapGraph
  ): List<Pair<HeapObject, GcRoot>> {
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

  private fun visitClassRecord(
    graph: HeapGraph,
    heapClass: HeapClass,
    parent: ReferencePathNode,
    staticFieldNameByClassName: Map<String, Map<String, ReferenceMatcher>>,
    computeRetainedHeapSize: Boolean
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
        undominateWithSkips(graph, objectId)
      }

      when (val referenceMatcher = ignoredStaticFields[fieldName]) {
        null -> {
          enqueue(
              graph,
              NormalNode(objectId, parent, LeakReference(STATIC_FIELD, fieldName))
          )
        }
        is LibraryLeakReferenceMatcher -> {
          enqueue(
              graph,
              LibraryLeakNode(
                  objectId, parent, LeakReference(STATIC_FIELD, fieldName), referenceMatcher
              )
          )
        }
      }
    }
  }

  private fun visitInstanceRecord(
    graph: HeapGraph,
    instance: HeapInstance,
    parent: ReferencePathNode,
    fieldNameByClassName: Map<String, Map<String, ReferenceMatcher>>,
    computeRetainedHeapSize: Boolean
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
        .toMutableList()

    fieldNamesAndValues.sortBy { it.name }

    fieldNamesAndValues.filter { it.value.isNonNullReference }
        .forEach { field ->
          val objectId = field.value.asObjectId!!
          if (computeRetainedHeapSize) {
            updateDominatorWithSkips(graph, parent.instance, objectId)
          }
          when (val referenceMatcher = fieldReferenceMatchers[field.name]) {
            null -> {
              enqueue(
                  graph,
                  NormalNode(objectId, parent, LeakReference(INSTANCE_FIELD, field.name))
              )
            }
            is LibraryLeakReferenceMatcher -> {
              enqueue(
                  graph,
                  LibraryLeakNode(
                      objectId, parent, LeakReference(INSTANCE_FIELD, field.name), referenceMatcher
                  )
              )
            }
          }
        }
  }

  private fun visitObjectArrayRecord(
    graph: HeapGraph,
    record: ObjectArrayDumpRecord,
    parentNode: ReferencePathNode,
    computeRetainedHeapSize: Boolean
  ) {
    record.elementIds.filter { objectId ->
      objectId != ValueHolder.NULL_REFERENCE && graph.objectExists(objectId).apply {
        if (!this) {
          // dalvik.system.PathClassLoader.runtimeInternalObjects references objects which don't
          // otherwise exist in the heap dump.
          SharkLog.d("Invalid Hprof? Found unknown object id $objectId")
        }
      }
    }
        .forEachIndexed { index, elementId ->
          if (computeRetainedHeapSize) {
            updateDominatorWithSkips(graph, parentNode.instance, elementId)
          }
          val name = Integer.toString(index)
          enqueue(
              graph,
              NormalNode(elementId, parentNode, LeakReference(ARRAY_ENTRY, name))
          )
        }
  }

  private fun enqueue(
    graph: HeapGraph,
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
    //
    if (toVisitLastSet.contains(node.instance)) {
      // Already enqueued => shorter or equal distance amongst library leak ref patterns.
      if (node is LibraryLeakNode) {
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
            graphObject.instanceClass.instanceSize <= sizeOfObjectInstances -> true
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
    if (node is LibraryLeakNode) {
      toVisitLastQueue.add(node)
      toVisitLastSet.add(node.instance)
    } else {
      toVisitQueue.add(node)
      toVisitSet.add(node.instance)
    }
  }

  private fun updateDominatorWithSkips(
    graph: HeapGraph,
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

  private fun updateDominator(
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

  private fun undominateWithSkips(
    graph: HeapGraph,
    objectId: Long
  ) {
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

  private fun undominate(
    instance: Long,
    neverEnqueued: Boolean
  ) {
    dominatedInstances.remove(instance)
    if (neverEnqueued) {
      visitedSet.add(instance)
    }
  }
}
