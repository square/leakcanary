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
import leakcanary.ExclusionsFactory
import leakcanary.GcRoot
import leakcanary.GcRoot.JavaFrame
import leakcanary.GcRoot.ThreadObject
import leakcanary.HeapValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HprofParser
import leakcanary.LeakNode
import leakcanary.LeakNode.ChildNode
import leakcanary.LeakNode.RootNode
import leakcanary.LeakReference
import leakcanary.LeakTraceElement.Type.ARRAY_ENTRY
import leakcanary.LeakTraceElement.Type.INSTANCE_FIELD
import leakcanary.LeakTraceElement.Type.LOCAL
import leakcanary.LeakTraceElement.Type.STATIC_FIELD
import leakcanary.ObjectIdMetadata.CLASS
import leakcanary.ObjectIdMetadata.EMPTY_INSTANCE
import leakcanary.ObjectIdMetadata.INSTANCE
import leakcanary.ObjectIdMetadata.OBJECT_ARRAY
import leakcanary.ObjectIdMetadata.PRIMITIVE_WRAPPER_ARRAY
import leakcanary.ObjectIdMetadata.PRIMITIVE_WRAPPER_OR_PRIMITIVE_ARRAY
import leakcanary.ObjectIdMetadata.STRING
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.internal.hppc.LongLongScatterMap
import leakcanary.internal.hppc.LongScatterSet
import leakcanary.reference
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
    parser: HprofParser,
    exclusionsFactory: ExclusionsFactory,
    leakingWeakRefs: List<KeyedWeakReferenceMirror>,
    gcRootIds: MutableList<GcRoot>,
    computeDominators: Boolean,
    listener: AnalyzerProgressListener
  ): Results {
    listener.onProgressUpdate(FINDING_SHORTEST_PATHS)
    clearState()

    val fieldNameByClassName = mutableMapOf<String, MutableMap<String, Exclusion>>()
    val staticFieldNameByClassName = mutableMapOf<String, MutableMap<String, Exclusion>>()
    val threadNames = mutableMapOf<String, Exclusion>()

    exclusionsFactory(parser)
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

    enqueueGcRoots(parser, gcRootIds, threadNames, computeDominators)

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

      when (val record = parser.retrieveRecordById(node.instance)) {
        is ClassDumpRecord -> visitClassRecord(
            parser, record, node, staticFieldNameByClassName, computeDominators
        )
        is InstanceDumpRecord -> visitInstanceRecord(
            parser, record, node, fieldNameByClassName, computeDominators
        )
        is ObjectArrayDumpRecord -> visitObjectArrayRecord(
            parser, record, node, computeDominators
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
  }

  private fun enqueueGcRoots(
    parser: HprofParser,
    gcRoots: MutableList<GcRoot>,
    threadNameExclusions: Map<String, Exclusion>,
    computeDominators: Boolean
  ) {
    gcRoots.removeAll { it.id == 0L }

    // Sorting GC roots to get stable shortest path
    // Once sorted all ThreadObject Gc Roots are located before JavaLocalExclusion Gc Roots.
    // This ensures ThreadObjects are visited before JavaFrames, and threadsBySerialNumber can be
    // built before JavaFrames.
    sortGcRoots(parser, gcRoots)

    val threadsBySerialNumber = mutableMapOf<Int, ThreadObject>()
    gcRoots.forEach { gcRoot ->
      if (computeDominators) {
        undominateWithSkips(parser, gcRoot.id)
      }
      when (gcRoot) {
        is ThreadObject -> {
          threadsBySerialNumber[gcRoot.threadSerialNumber] = gcRoot
          enqueue(parser, RootNode(gcRoot.id, visitOrder++), exclusionPriority = null)
        }
        is JavaFrame -> with(parser) {
          val threadRoot = threadsBySerialNumber.getValue(gcRoot.threadSerialNumber)
          val threadInstance = threadRoot.id.objectRecord.hydratedInstance
          val threadName = threadInstance["name"].reference.stringOrNull
          val exclusion = threadNameExclusions[threadName]

          if (exclusion == null || exclusion.status != NEVER_REACHABLE) {
            // visitOrder is unused as this root node isn't enqueued.
            val rootNode = RootNode(threadRoot.id, visitOrder = 0)
            // TODO #1352 Instead of <Java Local>, it should be <local variable in Foo.bar()>
            // We should also add the full stacktrace as a label of thread objects
            val leakReference = LeakReference(LOCAL, "")
            enqueue(
                parser,
                ChildNode(gcRoot.id, visitOrder++, exclusion?.description, rootNode, leakReference),
                exclusionPriority = exclusion?.status
            )
          }
        }
        else -> enqueue(parser, RootNode(gcRoot.id, visitOrder++), exclusionPriority = null)
      }
    }
    gcRoots.clear()
  }

  private fun sortGcRoots(
    parser: HprofParser,
    gcRoots: MutableList<GcRoot>
  ) {
    val rootClassName: (GcRoot) -> String = {
      when (val metadata = parser.objectIdMetadata(it.id)) {
        PRIMITIVE_WRAPPER_OR_PRIMITIVE_ARRAY, PRIMITIVE_WRAPPER_ARRAY, EMPTY_INSTANCE -> metadata.name
        STRING -> "java.lang.String"
        OBJECT_ARRAY -> {
          val record = parser.retrieveRecordById(it.id) as ObjectArrayDumpRecord
          parser.className(record.arrayClassId)
        }
        INSTANCE -> {
          val record = parser.retrieveRecordById(it.id) as InstanceDumpRecord
          parser.className(record.classId)
        }
        CLASS -> parser.className(it.id)
        else -> throw IllegalStateException("Unexpected type $metadata")
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
    hprofParser: HprofParser,
    record: ClassDumpRecord,
    node: LeakNode,
    staticFieldNameByClassName: Map<String, Map<String, Exclusion>>,
    computeRetainedHeapSize: Boolean
  ) {
    val className = hprofParser.className(record.id)

    val ignoredStaticFields = staticFieldNameByClassName[className] ?: emptyMap()

    for (staticField in record.staticFields) {
      val objectId = (staticField.value as? ObjectReference)?.value ?: continue
      val fieldName = hprofParser.hprofStringById(staticField.nameStringId)
      if (fieldName == "\$staticOverhead") {
        continue
      }

      if (computeRetainedHeapSize) {
        undominateWithSkips(hprofParser, objectId)
      }

      val leakReference = LeakReference(STATIC_FIELD, fieldName)

      val exclusion = ignoredStaticFields[fieldName]

      enqueue(
          hprofParser,
          ChildNode(objectId, visitOrder++, exclusion?.description, node, leakReference),
          exclusion?.status
      )
    }
  }

  private fun visitInstanceRecord(
    hprofParser: HprofParser,
    record: InstanceDumpRecord,
    parent: LeakNode,
    fieldNameByClassName: Map<String, Map<String, Exclusion>>,
    computeRetainedHeapSize: Boolean
  ) {
    val instance = hprofParser.hydrateInstance(record)

    val ignoredFields = LinkedHashMap<String, Exclusion>()

    instance.classHierarchy.forEach {
      val classExclusions = fieldNameByClassName[it.className]
      if (classExclusions != null) {
        for ((fieldName, exclusion) in classExclusions) {
          if (!ignoredFields.containsKey(fieldName)) {
            ignoredFields[fieldName] = exclusion
          }
        }
      }
    }

    val fieldNamesAndValues = mutableListOf<Pair<String, HeapValue>>()

    instance.fieldValues.forEachIndexed { classIndex, classFieldValues ->
      classFieldValues.forEachIndexed { fieldIndex, fieldValue ->
        val fieldName = instance.classHierarchy[classIndex].fieldNames[fieldIndex]
        fieldNamesAndValues.add(fieldName to fieldValue)
      }
    }

    fieldNamesAndValues.sortBy { (name, _) -> name }

    fieldNamesAndValues.filter { (_, value) -> value is ObjectReference }
        .map { (name, reference) -> name to (reference as ObjectReference).value }
        .forEach { (fieldName, objectId) ->
          if (computeRetainedHeapSize) {
            updateDominatorWithSkips(hprofParser, parent.instance, objectId)
          }

          val exclusion = ignoredFields[fieldName]
          enqueue(
              hprofParser, ChildNode(
              objectId,
              visitOrder++, exclusion?.description, parent,
              LeakReference(INSTANCE_FIELD, fieldName)
          ), exclusion?.status
          )
        }
  }

  private fun visitObjectArrayRecord(
    hprofParser: HprofParser,
    record: ObjectArrayDumpRecord,
    parentNode: LeakNode,
    computeRetainedHeapSize: Boolean
  ) {
    record.elementIds.forEachIndexed { index, elementId ->
      if (computeRetainedHeapSize) {
        updateDominatorWithSkips(hprofParser, parentNode.instance, elementId)
      }
      val name = Integer.toString(index)
      val reference = LeakReference(ARRAY_ENTRY, name)
      enqueue(hprofParser, ChildNode(elementId, visitOrder++, null, parentNode, reference), null)
    }
  }

  private fun enqueue(
    parser: HprofParser,
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

    val objectIdMetadata = parser.objectIdMetadata(node.instance)
    if (!isLeakingInstance && objectIdMetadata in SKIP_ENQUEUE) {
      return
    }

    if (existingPriority != null) {
      toVisitQueue.removeAll { it.instance == node.instance }
    }
    toVisitMap[node.instance] = nodePriority
    toVisitQueue.add(node)
  }

  private fun updateDominatorWithSkips(
    hprofParser: HprofParser,
    parentObjectId: Long,
    objectId: Long
  ) {
    when (hprofParser.objectIdMetadata(objectId)) {
      CLASS -> {
        undominate(objectId, false)
      }
      // String internal array is never enqueued
      STRING -> {
        updateDominator(parentObjectId, objectId, true)
        val stringRecord = hprofParser.retrieveRecordById(objectId) as InstanceDumpRecord
        val stringInstance = hprofParser.hydrateInstance(stringRecord)
        val valueId = stringInstance["value"].reference
        if (valueId != null) {
          updateDominator(parentObjectId, valueId, true)
        }
      }
      // Primitive wrapper array elements are never enqueued
      PRIMITIVE_WRAPPER_ARRAY -> {
        updateDominator(parentObjectId, objectId, true)
        val arrayRecord = hprofParser.retrieveRecordById(objectId) as ObjectArrayDumpRecord
        for (wrapperId in arrayRecord.elementIds) {
          updateDominator(parentObjectId, wrapperId, true)
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
    hprofParser: HprofParser,
    objectId: Long
  ) {
    when (hprofParser.objectIdMetadata(objectId)) {
      CLASS -> {
        undominate(objectId, false)
      }
      // String internal array is never enqueued
      STRING -> {
        undominate(objectId, true)
        val stringRecord = hprofParser.retrieveRecordById(objectId) as InstanceDumpRecord
        val stringInstance = hprofParser.hydrateInstance(stringRecord)
        val valueId = stringInstance["value"].reference
        if (valueId != null) {
          undominate(valueId, true)
        }
      }
      // Primitive wrapper array elements are never enqueued
      PRIMITIVE_WRAPPER_ARRAY -> {
        undominate(objectId, true)
        val arrayRecord = hprofParser.retrieveRecordById(objectId) as ObjectArrayDumpRecord
        for (wrapperId in arrayRecord.elementIds) {
          undominate(wrapperId, true)
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
    private val SKIP_ENQUEUE =
      setOf(PRIMITIVE_WRAPPER_OR_PRIMITIVE_ARRAY, PRIMITIVE_WRAPPER_ARRAY, STRING, EMPTY_INSTANCE)

    // Since NEVER_REACHABLE never ends up in the queue, we use its value to mean "ALWAYS_REACHABLE"
    // For this to work we need NEVER_REACHABLE to be declared as the first enum value.
    private val ALWAYS_REACHABLE = NEVER_REACHABLE
  }
}
