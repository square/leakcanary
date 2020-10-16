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
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.IgnoredReferenceMatcher
import shark.LeakTraceReference.ReferenceType.ARRAY_ENTRY
import shark.LeakTraceReference.ReferenceType.INSTANCE_FIELD
import shark.LeakTraceReference.ReferenceType.LOCAL
import shark.LeakTraceReference.ReferenceType.STATIC_FIELD
import shark.LibraryLeakReferenceMatcher
import shark.OnAnalysisProgressListener
import shark.OnAnalysisProgressListener.Step.FINDING_DOMINATORS
import shark.OnAnalysisProgressListener.Step.FINDING_PATHS_TO_RETAINED_OBJECTS
import shark.PrimitiveType
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.CHAR
import shark.PrimitiveType.DOUBLE
import shark.PrimitiveType.FLOAT
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT
import shark.ReferenceMatcher
import shark.ReferencePattern
import shark.ReferencePattern.InstanceFieldPattern
import shark.ReferencePattern.NativeGlobalVariablePattern
import shark.ReferencePattern.StaticFieldPattern
import shark.ValueHolder
import shark.ValueHolder.ReferenceHolder
import shark.internal.PathFinder.VisitTracker.Dominated
import shark.internal.PathFinder.VisitTracker.Visited
import shark.internal.ReferencePathNode.ChildNode
import shark.internal.ReferencePathNode.ChildNode.LibraryLeakChildNode
import shark.internal.ReferencePathNode.ChildNode.NormalNode
import shark.internal.ReferencePathNode.LibraryLeakNode
import shark.internal.ReferencePathNode.RootNode
import shark.internal.ReferencePathNode.RootNode.LibraryLeakRootNode
import shark.internal.ReferencePathNode.RootNode.NormalRootNode
import shark.internal.hppcshark.LongObjectPair
import shark.internal.hppcshark.LongScatterSet
import shark.internal.hppcshark.to
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

  private val fieldNameByClassName: Map<String, Map<String, ReferenceMatcher>>
  private val staticFieldNameByClassName: Map<String, Map<String, ReferenceMatcher>>
  private val threadNameReferenceMatchers: Map<String, ReferenceMatcher>
  private val jniGlobalReferenceMatchers: Map<String, ReferenceMatcher>

  init {
    val fieldNameByClassName = mutableMapOf<String, MutableMap<String, ReferenceMatcher>>()
    val staticFieldNameByClassName = mutableMapOf<String, MutableMap<String, ReferenceMatcher>>()
    val threadNames = mutableMapOf<String, ReferenceMatcher>()
    val jniGlobals = mutableMapOf<String, ReferenceMatcher>()

    val appliedRefMatchers = referenceMatchers.filter {
      (it is IgnoredReferenceMatcher || (it is LibraryLeakReferenceMatcher && it.patternApplies(
          graph
      )))
    }

    appliedRefMatchers.forEach { referenceMatcher ->
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
      if (node.objectId in leakingObjectIds) {
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

      when (val heapObject = graph.findObjectById(node.objectId)) {
        is HeapClass -> visitClassRecord(heapObject, node)
        is HeapInstance -> visitInstance(heapObject, node)
        is HeapObjectArray -> visitObjectArray(heapObject, node)
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

    val threadNames = mutableMapOf<HeapInstance, String>()
    val threadsBySerialNumber = mutableMapOf<Int, Pair<HeapInstance, ThreadObject>>()
    gcRoots.forEach { (objectRecord, gcRoot) ->
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
      if (fieldName == "\$staticOverhead" || fieldName == "\$classOverhead") {
        continue
      }

      // Note: instead of calling staticField.value.asObjectId!! we cast holder to ReferenceHolder
      // and access value directly. This allows us to avoid unnecessary boxing of Long.
      val objectId = (staticField.value.holder as ReferenceHolder).value

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
    val classHierarchy =
      instance.instanceClass.classHierarchyWithoutJavaLangObject(javaLangObjectId)
    val fieldNamesAndValues =
      instance.readAllNonNullFieldsOfReferenceType(classHierarchy)

    fieldNamesAndValues.sortBy { it.fieldName }

    fieldNamesAndValues.forEach { instanceRefField ->
      val node = when (val referenceMatcher = fieldReferenceMatchers[instanceRefField.fieldName]) {
        null -> NormalNode(
            objectId = instanceRefField.refObjectId,
            parent = parent,
            refFromParentType = INSTANCE_FIELD,
            refFromParentName = instanceRefField.fieldName,
            owningClassId = instanceRefField.declaringClassId
        )
        is LibraryLeakReferenceMatcher ->
          LibraryLeakChildNode(
              objectId = instanceRefField.refObjectId,
              parent = parent,
              refFromParentType = INSTANCE_FIELD,
              refFromParentName = instanceRefField.fieldName,
              matcher = referenceMatcher,
              owningClassId = instanceRefField.declaringClassId
          )
        is IgnoredReferenceMatcher -> null
      }
      if (node != null) {
        enqueue(node)
      }
    }
  }

  private class InstanceRefField(
    val declaringClassId: Long,
    val refObjectId: Long,
    val fieldName: String
  )

  private fun HeapInstance.readAllNonNullFieldsOfReferenceType(
    classHierarchy: List<HeapClass>
  ): MutableList<InstanceRefField> {
    // Assigning to local variable to avoid repeated lookup and cast:
    // HeapInstance.graph casts HeapInstance.hprofGraph to HeapGraph in its getter
    val hprofGraph = graph
    var fieldReader: FieldIdReader? = null
    val result = mutableListOf<InstanceRefField>()
    var skipBytesCount = 0

    for (heapClass in classHierarchy) {
      for (fieldRecord in heapClass.readRecordFields()) {
        if (fieldRecord.type != PrimitiveType.REFERENCE_HPROF_TYPE) {
          // Skip all fields that are not references. Track how many bytes to skip
          skipBytesCount += hprofGraph.getRecordSize(fieldRecord)
        } else {
          // Initialize id reader if it's not yet initialized. Replaces `lazy` without synchronization
          if (fieldReader == null) {
            fieldReader = FieldIdReader(readRecord(), hprofGraph.identifierByteSize)
          }

          // Skip the accumulated bytes offset
          fieldReader.skipBytes(skipBytesCount)
          skipBytesCount = 0

          val objectId = fieldReader.readId()
          if (objectId != 0L) {
            result.add(
                InstanceRefField(
                    heapClass.objectId, objectId, heapClass.instanceFieldName(fieldRecord)
                )
            )
          }
        }
      }
    }
    return result
  }

  /**
   * Returns class hierarchy for an instance, but without it's root element, which is always
   * java.lang.Object.
   * Why do we want class hierarchy without java.lang.Object?
   * In pre-M there were no ref fields in java.lang.Object; and FieldIdReader wouldn't be created
   * Android M added shadow$_klass_ reference to class, so now it triggers extra record read.
   * Solution: skip heap class for java.lang.Object completely when reading the records
   * @param javaLangObjectId ID of the java.lang.Object to run comparison against
   */
  private fun HeapClass.classHierarchyWithoutJavaLangObject(
    javaLangObjectId: Long
  ): List<HeapClass> {
    val result = mutableListOf<HeapClass>()
    var parent: HeapClass? = this
    while (parent != null && parent.objectId != javaLangObjectId) {
      result += parent
      parent = parent.superclass
    }
    return result
  }

  private fun HeapGraph.getRecordSize(field: FieldRecord) =
    when (field.type) {
      PrimitiveType.REFERENCE_HPROF_TYPE -> identifierByteSize
      BOOLEAN.hprofType -> 1
      CHAR.hprofType -> 2
      FLOAT.hprofType -> 4
      DOUBLE.hprofType -> 8
      BYTE.hprofType -> 1
      SHORT.hprofType -> 2
      INT.hprofType -> 4
      LONG.hprofType -> 8
      else -> throw IllegalStateException("Unknown type ${field.type}")
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

    val visitLast =
      visitingLast ||
          node is LibraryLeakNode ||
          // We deprioritize thread objects because on Lollipop the thread local values are stored
          // as a field.
          (node is RootNode && node.gcRoot is ThreadObject) ||
          (node is NormalNode && node.parent is RootNode && node.parent.gcRoot is JavaFrame)

    val parentObjectId = if (node is RootNode) {
      ValueHolder.NULL_REFERENCE
    } else {
      (node as ChildNode).parent.objectId
    }

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

    val isLeakingObject = node.objectId in leakingObjectIds

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

