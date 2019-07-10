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
package leakcanary.internal

import leakcanary.AnalyzerProgressListener
import leakcanary.AnalyzerProgressListener.Step.FINDING_DOMINATORS
import leakcanary.AnalyzerProgressListener.Step.FINDING_SHORTEST_PATHS
import leakcanary.Exclusion
import leakcanary.Exclusion.ExclusionType
import leakcanary.Exclusion.ExclusionType.InstanceFieldExclusion
import leakcanary.Exclusion.ExclusionType.StaticFieldExclusion
import leakcanary.Exclusion.Status
import leakcanary.Exclusion.Status.NEVER_REACHABLE
import leakcanary.Exclusion.Status.WEAKLY_REACHABLE
import leakcanary.GcRoot
import leakcanary.GcRoot.JavaFrame
import leakcanary.GcRoot.ThreadObject
import leakcanary.GraphObjectRecord.GraphClassRecord
import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.GraphObjectRecord.GraphObjectArrayRecord
import leakcanary.GraphObjectRecord.GraphPrimitiveArrayRecord
import leakcanary.HprofGraph
import leakcanary.HprofReader
import leakcanary.LeakNode
import leakcanary.LeakNode.ChildNode
import leakcanary.LeakNode.RootNode
import leakcanary.LeakReference
import leakcanary.LeakTraceElement.Type.ARRAY_ENTRY
import leakcanary.LeakTraceElement.Type.INSTANCE_FIELD
import leakcanary.LeakTraceElement.Type.LOCAL
import leakcanary.LeakTraceElement.Type.STATIC_FIELD
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.internal.hppc.LongLongScatterMap
import leakcanary.internal.hppc.LongScatterSet
import java.util.LinkedHashMap
import java.util.PriorityQueue

/**
 * Not thread safe.
 *
 * Finds the shortest path from leaking references to a gc root, ignoring excluded
 * refs first and then including the ones that are not "always ignorable" as needed if no path is
 * found.
 *
 * Skips enqueuing strings as an optimization, so if the leaking reference is a string then it will
 * never be found.
 */
internal class ShortestPathFinder {

  /**
   * A segmented FIFO queue. The queue is segmented by [Status]. Within each segment the elements
   * are ordered FIFO.
   */
  private val toVisitQueue = PriorityQueue<LeakNode>(1024, Comparator { node1, node2 ->
    val priorityComparison = toVisitMap[node1.instance]!!.compareTo(toVisitMap[node2.instance]!!)
    if (priorityComparison != 0) {
      priorityComparison
    } else {
      node1.visitOrder.compareTo(node2.visitOrder)
    }
  })
  /** Set of instances to visit */
  private val toVisitMap = LinkedHashMap<Long, Status>()
  private val visitedSet = LongScatterSet()
  private lateinit var referentMap: Map<Long, KeyedWeakReferenceMirror>
  private var visitOrder = 0

  /**
   * Map of instances to their leaking dominator.
   * var because the instance will be returned by [findPaths] and replaced with a new empty map
   * here (copying it could be expensive).
   *
   * If an instance has been added to [toVisitMap] or [visitedSet] and is missing from
   * [dominatedInstances] then it's considered "undomitable" ie it is dominated by gc roots
   * and cannot be dominated by a leaking instance.
   */
  private var dominatedInstances = LongLongScatterMap()
  private var sizeOfObjectInstances = 0

  class Result(
    val leakingNode: LeakNode,
    val exclusionStatus: Status?,
    val weakReference: KeyedWeakReferenceMirror
  )

  data class Results(
    val results: List<Result>,
    val dominatedInstances: LongLongScatterMap
  )

  fun findPaths(
    graph: HprofGraph,
    exclusions: List<Exclusion>,
    leakingWeakRefs: List<KeyedWeakReferenceMirror>,
    gcRootIds: MutableList<GcRoot>,
    computeDominators: Boolean,
    listener: AnalyzerProgressListener
  ): Results {
    listener.onProgressUpdate(FINDING_SHORTEST_PATHS)
    clearState()

    val objectClass = graph.indexedClass("java.lang.Object")
    sizeOfObjectInstances = if (objectClass != null) {
      // In Android 16 ClassDumpRecord.instanceSize can be 8 yet there are 0 fields.
      // Better rely on our own computation of instance size.
      // See #1374
      val objectClassFieldSize = objectClass.readRecord()
          .fields.sumBy {
        graph.sizeOfFieldType(it.type)
      }

      // shadow$_klass_ (object id) + shadow$_monitor_ (Int)
      val sizeOfObjectOnArt =
        graph.sizeOfFieldType(HprofReader.OBJECT_TYPE) + graph.sizeOfFieldType(HprofReader.INT_TYPE)
      if (objectClassFieldSize == sizeOfObjectOnArt) {
        sizeOfObjectOnArt
      } else {
        0
      }
    } else {
      0
    }

    val fieldNameByClassName = mutableMapOf<String, MutableMap<String, Exclusion>>()
    val staticFieldNameByClassName = mutableMapOf<String, MutableMap<String, Exclusion>>()
    val threadNames = mutableMapOf<String, Exclusion>()

    exclusions.filter { it.filter(graph) }
        .forEach { exclusion ->
          when (exclusion.type) {
            is ExclusionType.JavaLocalExclusion -> {
              threadNames[exclusion.type.threadName] = exclusion
            }
            is StaticFieldExclusion -> {
              val mapOrNull = staticFieldNameByClassName[exclusion.type.className]
              val map = if (mapOrNull != null) mapOrNull else {
                val newMap = mutableMapOf<String, Exclusion>()
                staticFieldNameByClassName[exclusion.type.className] = newMap
                newMap
              }
              map[exclusion.type.fieldName] = exclusion
            }
            is InstanceFieldExclusion -> {
              val mapOrNull = fieldNameByClassName[exclusion.type.className]
              val map = if (mapOrNull != null) mapOrNull else {
                val newMap = mutableMapOf<String, Exclusion>()
                fieldNameByClassName[exclusion.type.className] = newMap
                newMap
              }
              map[exclusion.type.fieldName] = exclusion
            }
          }
        }

    // Referent object id to weak ref mirror
    referentMap = leakingWeakRefs.associateBy { it.referent.value }

    enqueueGcRoots(graph, gcRootIds, threadNames, computeDominators)

    var lowestPriority = ALWAYS_REACHABLE
    val results = mutableListOf<Result>()
    visitingQueue@ while (!toVisitQueue.isEmpty()) {
      val node = toVisitQueue.poll()!!
      val priority = toVisitMap[node.instance]!!
      // Lowest priority has the highest value
      if (priority > lowestPriority) {
        lowestPriority = priority
      }

      toVisitMap.remove(node.instance)

      if (checkSeen(node)) {
        continue
      }

      val weakReference = referentMap[node.instance]
      if (weakReference != null) {
        val exclusionPriority = if (lowestPriority == ALWAYS_REACHABLE) null else lowestPriority
        results.add(Result(node, exclusionPriority, weakReference))
        // Found all refs, stop searching (unless computing retained size which stops on weak reachables)
        if (results.size == leakingWeakRefs.size) {
          if (computeDominators && lowestPriority < WEAKLY_REACHABLE) {
            listener.onProgressUpdate(FINDING_DOMINATORS)
          } else {
            break@visitingQueue
          }
        }
      }

      if (results.size == leakingWeakRefs.size && computeDominators && lowestPriority >= WEAKLY_REACHABLE) {
        break@visitingQueue
      }

      when (val graphRecord = graph.indexedObject(node.instance)) {
        is GraphClassRecord -> visitClassRecord(
            graph, graphRecord, node, staticFieldNameByClassName, computeDominators
        )
        is GraphInstanceRecord -> visitInstanceRecord(
            graph, graphRecord, node, fieldNameByClassName, computeDominators
        )
        is GraphObjectArrayRecord -> visitObjectArrayRecord(
            graph, graphRecord.readRecord(), node, computeDominators
        )
      }
    }

    val dominatedInstances = this.dominatedInstances

    clearState()

    return Results(results, dominatedInstances)
  }

  private fun checkSeen(node: LeakNode): Boolean {
    val neverSeen = visitedSet.add(node.instance)
    return !neverSeen
  }

  private fun clearState() {
    toVisitQueue.clear()
    toVisitMap.clear()
    visitedSet.release()
    visitOrder = 0
    referentMap = emptyMap()
    dominatedInstances = LongLongScatterMap()
    sizeOfObjectInstances = 0
  }

  private fun enqueueGcRoots(
    graph: HprofGraph,
    gcRoots: MutableList<GcRoot>,
    threadNameExclusions: Map<String, Exclusion>,
    computeDominators: Boolean
  ) {
    gcRoots.removeAll { it.id == 0L }

    // Sorting GC roots to get stable shortest path
    // Once sorted all ThreadObject Gc Roots are located before JavaLocalExclusion Gc Roots.
    // This ensures ThreadObjects are visited before JavaFrames, and threadsBySerialNumber can be
    // built before JavaFrames.
    sortGcRoots(graph, gcRoots)

    val threadsBySerialNumber = mutableMapOf<Int, ThreadObject>()
    gcRoots.forEach { gcRoot ->
      if (computeDominators) {
        undominateWithSkips(graph, gcRoot.id)
      }
      when (gcRoot) {
        is ThreadObject -> {
          threadsBySerialNumber[gcRoot.threadSerialNumber] = gcRoot
          enqueue(graph, RootNode(gcRoot.id, visitOrder++), exclusionPriority = null)
        }
        is JavaFrame -> {
          val threadRoot = threadsBySerialNumber.getValue(gcRoot.threadSerialNumber)
          val threadInstance = graph.indexedObject(threadRoot.id).asInstance!!
          val threadName = threadInstance[Thread::class, "name"]?.value?.readAsJavaString()
          val exclusion = threadNameExclusions[threadName]

          if (exclusion == null || exclusion.status != NEVER_REACHABLE) {
            // visitOrder is unused as this root node isn't enqueued.
            val rootNode = RootNode(threadRoot.id, visitOrder = 0)
            // TODO #1352 Instead of <Java Local>, it should be <local variable in Foo.bar()>
            // We should also add the full stacktrace as a label of thread objects
            val leakReference = LeakReference(LOCAL, "")
            enqueue(
                graph,
                ChildNode(gcRoot.id, visitOrder++, exclusion?.description, rootNode, leakReference),
                exclusionPriority = exclusion?.status
            )
          }
        }
        else -> enqueue(graph, RootNode(gcRoot.id, visitOrder++), exclusionPriority = null)
      }
    }
    gcRoots.clear()
  }

  private fun sortGcRoots(
    graph: HprofGraph,
    gcRoots: MutableList<GcRoot>
  ) {
    val rootClassName: (GcRoot) -> String = {
      when (val graphObject = graph.indexedObject(it.id)) {
        is GraphClassRecord -> {
          graphObject.name
        }
        is GraphInstanceRecord -> {
          graphObject.className
        }
        is GraphObjectArrayRecord -> {
          graphObject.arrayClassName
        }
        is GraphPrimitiveArrayRecord -> {
          graphObject.primitiveType.name
        }
      }
    }
    gcRoots.sortWith(Comparator { root1, root2 ->
      // Sorting based on type name first. In reverse order so that ThreadObject is before JavaLocalExclusion
      val gcRootTypeComparison = root2::class.java.name.compareTo(root1::class.java.name)
      if (gcRootTypeComparison != 0) {
        gcRootTypeComparison
      } else {
        rootClassName(root1).compareTo(rootClassName(root2))
      }
    })
  }

  private fun visitClassRecord(
    graph: HprofGraph,
    classRecord: GraphClassRecord,
    node: LeakNode,
    staticFieldNameByClassName: Map<String, Map<String, Exclusion>>,
    computeRetainedHeapSize: Boolean
  ) {
    val ignoredStaticFields = staticFieldNameByClassName[classRecord.name] ?: emptyMap()

    for (staticField in classRecord.readStaticFields()) {
      if (!staticField.value.isNonNullReference) {
        continue
      }

      val fieldName = staticField.name
      if (fieldName == "\$staticOverhead") {
        continue
      }

      val objectId = staticField.value.asObjectIdReference!!

      if (computeRetainedHeapSize) {
        undominateWithSkips(graph, objectId)
      }

      val leakReference = LeakReference(STATIC_FIELD, fieldName)

      val exclusion = ignoredStaticFields[fieldName]

      enqueue(
          graph,
          ChildNode(objectId, visitOrder++, exclusion?.description, node, leakReference),
          exclusion?.status
      )
    }
  }

  private fun visitInstanceRecord(
    graph: HprofGraph,
    instanceRecord: GraphInstanceRecord,
    parent: LeakNode,
    fieldNameByClassName: Map<String, Map<String, Exclusion>>,
    computeRetainedHeapSize: Boolean
  ) {
    val ignoredFields = LinkedHashMap<String, Exclusion>()

    instanceRecord.instanceClass.classHierarchy.forEach {
      val classExclusions = fieldNameByClassName[it.name]
      if (classExclusions != null) {
        for ((fieldName, exclusion) in classExclusions) {
          if (!ignoredFields.containsKey(fieldName)) {
            ignoredFields[fieldName] = exclusion
          }
        }
      }
    }

    val fieldNamesAndValues = instanceRecord.readFields()
        .toMutableList()

    fieldNamesAndValues.sortBy { it.name }

    fieldNamesAndValues.filter { it.value.isNonNullReference }
        .forEach { field ->
          val objectId = field.value.asObjectIdReference!!
          if (computeRetainedHeapSize) {
            updateDominatorWithSkips(graph, parent.instance, objectId)
          }

          val exclusion = ignoredFields[field.name]
          enqueue(
              graph, ChildNode(
              objectId,
              visitOrder++, exclusion?.description, parent,
              LeakReference(INSTANCE_FIELD, field.name)
          ), exclusion?.status
          )
        }
  }

  private fun visitObjectArrayRecord(
    graph: HprofGraph,
    record: ObjectArrayDumpRecord,
    parentNode: LeakNode,
    computeRetainedHeapSize: Boolean
  ) {
    record.elementIds.forEachIndexed { index, elementId ->
      if (computeRetainedHeapSize) {
        updateDominatorWithSkips(graph, parentNode.instance, elementId)
      }
      val name = Integer.toString(index)
      val reference = LeakReference(ARRAY_ENTRY, name)
      enqueue(graph, ChildNode(elementId, visitOrder++, null, parentNode, reference), null)
    }
  }

  private fun enqueue(
    graph: HprofGraph,
    node: LeakNode,
    exclusionPriority: Status?
  ) {
    // 0L is null
    if (node.instance == 0L) {
      return
    }
    if (visitedSet.contains(node.instance)) {
      return
    }
    if (exclusionPriority == NEVER_REACHABLE) {
      return
    }

    val nodePriority = exclusionPriority ?: ALWAYS_REACHABLE

    // Whether we want to visit now or later, we should skip if this is already to visit.
    val existingPriority = toVisitMap[node.instance]

    if (existingPriority != null && existingPriority <= nodePriority) {
      return
    }

    val isLeakingInstance = referentMap[node.instance] != null

    if (!isLeakingInstance) {
      val skip = when (val graphObject = graph.indexedObject(node.instance)) {
        is GraphClassRecord -> false
        is GraphInstanceRecord ->
          when {
            graphObject.isPrimitiveWrapper -> true
            graphObject.className == "java.lang.String" -> true
            graphObject.instanceClass.instanceSize <= sizeOfObjectInstances -> true
            else -> false
          }
        is GraphObjectArrayRecord -> when {
          graphObject.isPrimitiveWrapperArray -> true
          else -> false
        }
        is GraphPrimitiveArrayRecord -> true
      }
      if (skip) {
        return
      }
    }

    if (existingPriority != null) {
      toVisitQueue.removeAll { it.instance == node.instance }
    }
    toVisitMap[node.instance] = nodePriority
    toVisitQueue.add(node)
  }

  private fun updateDominatorWithSkips(
    graph: HprofGraph,
    parentObjectId: Long,
    objectId: Long
  ) {

    when (val graphObject = graph.indexedObject(objectId)) {
      is GraphClassRecord -> {
        undominate(objectId, false)
      }
      is GraphInstanceRecord -> {
        // String internal array is never enqueued
        if (graphObject.className == "java.lang.String") {
          updateDominator(parentObjectId, objectId, true)
          val valueId = graphObject["java.lang.String", "value"]?.value?.asObjectIdReference
          if (valueId != null) {
            updateDominator(parentObjectId, valueId, true)
          }
        } else {
          updateDominator(parentObjectId, objectId, false)
        }
      }
      is GraphObjectArrayRecord -> {
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
    if (currentDominator == null && (instance in visitedSet || instance in toVisitMap)) {
      return
    }
    val parentDominator = dominatedInstances[parent]

    val parentIsRetainedInstance = referentMap.containsKey(parent)

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
    graph: HprofGraph,
    objectId: Long
  ) {
    when (val graphObject = graph.indexedObject(objectId)) {
      is GraphClassRecord -> {
        undominate(objectId, false)
      }
      is GraphInstanceRecord -> {
        // String internal array is never enqueued
        if (graphObject.className == "java.lang.String") {
          undominate(objectId, true)
          val valueId = graphObject["java.lang.String", "value"]?.value?.asObjectIdReference
          if (valueId != null) {
            undominate(valueId, true)
          }
        } else {
          undominate(objectId, false)
        }
      }
      is GraphObjectArrayRecord -> {
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

  companion object {
    // Since NEVER_REACHABLE never ends up in the queue, we use its value to mean "ALWAYS_REACHABLE"
    // For this to work we need NEVER_REACHABLE to be declared as the first enum value.
    private val ALWAYS_REACHABLE = NEVER_REACHABLE
  }
}
