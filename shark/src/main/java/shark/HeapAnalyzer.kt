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
package shark

import shark.HeapAnalyzer.TrieNode.LeafNode
import shark.HeapAnalyzer.TrieNode.ParentNode
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.LeakTrace.GcRootType
import shark.LeakTraceObject.LeakingStatus
import shark.LeakTraceObject.LeakingStatus.LEAKING
import shark.LeakTraceObject.LeakingStatus.NOT_LEAKING
import shark.LeakTraceObject.LeakingStatus.UNKNOWN
import shark.LeakTraceObject.ObjectType.ARRAY
import shark.LeakTraceObject.ObjectType.CLASS
import shark.LeakTraceObject.ObjectType.INSTANCE
import shark.OnAnalysisProgressListener.Step.BUILDING_LEAK_TRACES
import shark.OnAnalysisProgressListener.Step.COMPUTING_NATIVE_RETAINED_SIZE
import shark.OnAnalysisProgressListener.Step.COMPUTING_RETAINED_SIZE
import shark.OnAnalysisProgressListener.Step.EXTRACTING_METADATA
import shark.OnAnalysisProgressListener.Step.FINDING_RETAINED_OBJECTS
import shark.OnAnalysisProgressListener.Step.PARSING_HEAP_DUMP
import shark.internal.PathFinder
import shark.internal.PathFinder.PathFindingResults
import shark.internal.ReferencePathNode
import shark.internal.ReferencePathNode.ChildNode
import shark.internal.ReferencePathNode.LibraryLeakNode
import shark.internal.ReferencePathNode.RootNode
import shark.internal.createSHA1Hash
import shark.internal.lastSegment
import java.io.File
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit.NANOSECONDS

/**
 * Analyzes heap dumps to look for leaks.
 */
@Suppress("TooManyFunctions")
class HeapAnalyzer constructor(
  private val listener: OnAnalysisProgressListener
) {

  private class FindLeakInput(
    val graph: HeapGraph,
    val referenceMatchers: List<ReferenceMatcher>,
    val computeRetainedHeapSize: Boolean,
    val objectInspectors: List<ObjectInspector>
  )

  /**
   * Searches the heap dump for leaking instances and then computes the shortest strong reference
   * path from those instances to the GC roots.
   */
  fun analyze(
    heapDumpFile: File,
    leakingObjectFinder: LeakingObjectFinder,
    referenceMatchers: List<ReferenceMatcher> = emptyList(),
    computeRetainedHeapSize: Boolean = false,
    objectInspectors: List<ObjectInspector> = emptyList(),
    metadataExtractor: MetadataExtractor = MetadataExtractor.NO_OP,
    proguardMapping: ProguardMapping? = null
  ): HeapAnalysis {
    val analysisStartNanoTime = System.nanoTime()

    if (!heapDumpFile.exists()) {
      val exception = IllegalArgumentException("File does not exist: $heapDumpFile")
      return HeapAnalysisFailure(
          heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
          HeapAnalysisException(exception)
      )
    }

    return try {
      listener.onAnalysisProgress(PARSING_HEAP_DUMP)
      Hprof.open(heapDumpFile)
          .use { hprof ->
            val graph = HprofHeapGraph.indexHprof(hprof, proguardMapping)
            val helpers =
              FindLeakInput(graph, referenceMatchers, computeRetainedHeapSize, objectInspectors)
            helpers.analyzeGraph(
                metadataExtractor, leakingObjectFinder, heapDumpFile, analysisStartNanoTime
            )
          }
    } catch (exception: Throwable) {
      HeapAnalysisFailure(
          heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
          HeapAnalysisException(exception)
      )
    }
  }

  fun analyze(
    heapDumpFile: File,
    graph: HeapGraph,
    leakingObjectFinder: LeakingObjectFinder,
    referenceMatchers: List<ReferenceMatcher> = emptyList(),
    computeRetainedHeapSize: Boolean = false,
    objectInspectors: List<ObjectInspector> = emptyList(),
    metadataExtractor: MetadataExtractor = MetadataExtractor.NO_OP
  ): HeapAnalysis {
    val analysisStartNanoTime = System.nanoTime()
    return try {
      val helpers =
        FindLeakInput(graph, referenceMatchers, computeRetainedHeapSize, objectInspectors)
      helpers.analyzeGraph(
          metadataExtractor, leakingObjectFinder, heapDumpFile, analysisStartNanoTime
      )
    } catch (exception: Throwable) {
      HeapAnalysisFailure(
          heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
          HeapAnalysisException(exception)
      )
    }
  }

  private fun FindLeakInput.analyzeGraph(
    metadataExtractor: MetadataExtractor,
    leakingObjectFinder: LeakingObjectFinder,
    heapDumpFile: File,
    analysisStartNanoTime: Long
  ): HeapAnalysisSuccess {
    listener.onAnalysisProgress(EXTRACTING_METADATA)
    val metadata = metadataExtractor.extractMetadata(graph)

    listener.onAnalysisProgress(FINDING_RETAINED_OBJECTS)
    val leakingObjectIds = leakingObjectFinder.findLeakingObjectIds(graph)

    val (applicationLeaks, libraryLeaks) = findLeaks(leakingObjectIds)

    return HeapAnalysisSuccess(
        heapDumpFile = heapDumpFile,
        createdAtTimeMillis = System.currentTimeMillis(),
        analysisDurationMillis = since(analysisStartNanoTime),
        metadata = metadata,
        applicationLeaks = applicationLeaks,
        libraryLeaks = libraryLeaks
    )
  }

  private fun FindLeakInput.findLeaks(leakingObjectIds: Set<Long>): Pair<List<ApplicationLeak>, List<LibraryLeak>> {
    val pathFinder = PathFinder(graph, listener, referenceMatchers)
    val pathFindingResults =
      pathFinder.findPathsFromGcRoots(leakingObjectIds, computeRetainedHeapSize)

    SharkLog.d { "Found ${leakingObjectIds.size} retained objects" }

    return buildLeakTraces(pathFindingResults)
  }

  internal sealed class TrieNode {
    abstract val objectId: Long

    class ParentNode(override val objectId: Long) : TrieNode() {
      val children = mutableMapOf<Long, TrieNode>()
      override fun toString(): String {
        return "ParentNode(objectId=$objectId, children=$children)"
      }
    }

    class LeafNode(
      override val objectId: Long,
      val pathNode: ReferencePathNode
    ) : TrieNode()

  }

  private fun deduplicateShortestPaths(inputPathResults: List<ReferencePathNode>): List<ReferencePathNode> {
    val rootTrieNode = ParentNode(0)

    for (pathNode in inputPathResults) {
      // Go through the linked list of nodes and build the reverse list of instances from
      // root to leaking.
      val path = mutableListOf<Long>()
      var leakNode: ReferencePathNode = pathNode
      while (leakNode is ChildNode) {
        path.add(0, leakNode.objectId)
        leakNode = leakNode.parent
      }
      path.add(0, leakNode.objectId)
      updateTrie(pathNode, path, 0, rootTrieNode)
    }

    val outputPathResults = mutableListOf<ReferencePathNode>()
    findResultsInTrie(rootTrieNode, outputPathResults)
    return outputPathResults
  }

  private fun updateTrie(
    pathNode: ReferencePathNode,
    path: List<Long>,
    pathIndex: Int,
    parentNode: ParentNode
  ) {
    val objectId = path[pathIndex]
    if (pathIndex == path.lastIndex) {
      // Replace any preexisting children, this is shorter.
      parentNode.children[objectId] = LeafNode(objectId, pathNode)
    } else {
      val childNode = parentNode.children[objectId] ?: {
        val newChildNode = ParentNode(objectId)
        parentNode.children[objectId] = newChildNode
        newChildNode
      }()
      if (childNode is ParentNode) {
        updateTrie(pathNode, path, pathIndex + 1, childNode)
      }
    }
  }

  private fun findResultsInTrie(
    parentNode: ParentNode,
    outputPathResults: MutableList<ReferencePathNode>
  ) {
    parentNode.children.values.forEach { childNode ->
      when (childNode) {
        is ParentNode -> {
          findResultsInTrie(childNode, outputPathResults)
        }
        is LeafNode -> {
          outputPathResults += childNode.pathNode
        }
      }
    }
  }

  private fun FindLeakInput.computeRetainedSizes(pathFindingResults: PathFindingResults): List<Int>? {
    if (!computeRetainedHeapSize) {
      return null
    }
    val pathsToLeakingInstances = pathFindingResults.pathsToLeakingObjects
    val dominatedInstances = pathFindingResults.dominatedObjectIds

    listener.onAnalysisProgress(COMPUTING_NATIVE_RETAINED_SIZE)

    // Map of Object id to native size as tracked by NativeAllocationRegistry$CleanerThunk
    val nativeSizes = mutableMapOf<Long, Int>().withDefault { 0 }
    // Doc from perflib:
    // Native allocations can be identified by looking at instances of
    // libcore.util.NativeAllocationRegistry$CleanerThunk. The "owning" Java object is the
    // "referent" field of the "sun.misc.Cleaner" instance with a hard reference to the
    // CleanerThunk.
    //
    // The size is in the 'size' field of the libcore.util.NativeAllocationRegistry instance
    // that the CleanerThunk has a pointer to. The native pointer is in the 'nativePtr' field of
    // the CleanerThunk. The hprof does not include the native bytes pointed to.

    graph.instances
        .filter { it.instanceClassName == "sun.misc.Cleaner" }
        .forEach { cleaner ->
          val thunkField = cleaner["sun.misc.Cleaner", "thunk"]
          val thunkId = thunkField?.value?.asNonNullObjectId
          val referentId =
            cleaner["java.lang.ref.Reference", "referent"]?.value?.asNonNullObjectId
          if (thunkId != null && referentId != null) {
            val thunkRecord = thunkField.value.asObject
            if (thunkRecord is HeapInstance && thunkRecord instanceOf "libcore.util.NativeAllocationRegistry\$CleanerThunk") {
              val allocationRegistryIdField =
                thunkRecord["libcore.util.NativeAllocationRegistry\$CleanerThunk", "this\$0"]
              if (allocationRegistryIdField != null && allocationRegistryIdField.value.isNonNullReference) {
                val allocationRegistryRecord = allocationRegistryIdField.value.asObject
                if (allocationRegistryRecord is HeapInstance && allocationRegistryRecord instanceOf "libcore.util.NativeAllocationRegistry") {
                  var nativeSize = nativeSizes.getValue(referentId)
                  nativeSize += allocationRegistryRecord["libcore.util.NativeAllocationRegistry", "size"]?.value?.asLong?.toInt()
                      ?: 0
                  nativeSizes[referentId] = nativeSize
                }
              }
            }
          }
        }

    listener.onAnalysisProgress(COMPUTING_RETAINED_SIZE)

    val sizeByDominator = LinkedHashMap<Long, Int>().withDefault { 0 }

    // Include self size for leaking instances
    val leakingInstanceIds = mutableSetOf<Long>()
    pathsToLeakingInstances.forEach { pathNode ->
      val leakingInstanceObjectId = pathNode.objectId
      leakingInstanceIds.add(leakingInstanceObjectId)
      val instanceRecord = graph.findObjectById(leakingInstanceObjectId).asInstance!!
      val heapClass = instanceRecord.instanceClass
      var retainedSize = sizeByDominator.getValue(leakingInstanceObjectId)

      retainedSize += heapClass.instanceByteSize
      sizeByDominator[leakingInstanceObjectId] = retainedSize
    }

    // Compute the size of each dominated instance and add to dominator
    dominatedInstances.forEach { instanceId, dominatorId ->
      // Avoid double reporting as those sizes will move up to the root dominator
      if (instanceId !in leakingInstanceIds) {
        val currentSize = sizeByDominator.getValue(dominatorId)
        val nativeSize = nativeSizes.getValue(instanceId)
        val shallowSize = when (val objectRecord = graph.findObjectById(instanceId)) {
          is HeapInstance -> objectRecord.byteSize
          is HeapObjectArray -> objectRecord.readByteSize()
          is HeapPrimitiveArray -> objectRecord.readByteSize()
          is HeapClass -> throw IllegalStateException(
              "Unexpected class record $objectRecord"
          )
        }
        sizeByDominator[dominatorId] = currentSize + nativeSize + shallowSize
      }
    }

    // Move retained sizes from dominated leaking instance to dominators leaking instances.
    // Keep doing this until nothing moves.
    var sizedMoved: Boolean
    do {
      sizedMoved = false
      pathsToLeakingInstances.map { it.objectId }
          .forEach { leakingInstanceId ->
            val dominatorSlot = dominatedInstances.getSlot(leakingInstanceId)
            if (dominatorSlot != -1) {
              val dominator = dominatedInstances.getSlotValue(dominatorSlot)
              val retainedSize = sizeByDominator.getValue(leakingInstanceId)
              if (retainedSize > 0) {
                sizeByDominator[leakingInstanceId] = 0
                val dominatorRetainedSize = sizeByDominator.getValue(dominator)
                sizeByDominator[dominator] = retainedSize + dominatorRetainedSize
                sizedMoved = true
              }
            }
          }
    } while (sizedMoved)
    dominatedInstances.release()
    return pathsToLeakingInstances.map { pathNode ->
      sizeByDominator[pathNode.objectId]!!
    }
  }

  private fun FindLeakInput.buildLeakTraces(pathFindingResults: PathFindingResults): Pair<List<ApplicationLeak>, List<LibraryLeak>> {
    val retainedSizes = computeRetainedSizes(pathFindingResults)

    listener.onAnalysisProgress(BUILDING_LEAK_TRACES)

    val applicationLeaksMap = mutableMapOf<String, MutableList<LeakTrace>>()
    val libraryLeaksMap =
      mutableMapOf<String, Pair<LibraryLeakReferenceMatcher, MutableList<LeakTrace>>>()

    val deduplicatedPaths = deduplicateShortestPaths(pathFindingResults.pathsToLeakingObjects)

    if (deduplicatedPaths.size != pathFindingResults.pathsToLeakingObjects.size) {
      SharkLog.d {
        "Found ${pathFindingResults.pathsToLeakingObjects.size} paths to retained objects," +
            " down to ${deduplicatedPaths.size} after removing duplicated paths"
      }
    } else {
      SharkLog.d { "Found ${deduplicatedPaths.size} paths to retained objects" }
    }

    deduplicatedPaths.forEachIndexed { index, retainedObjectNode ->

      val pathHeapObjects = mutableListOf<HeapObject>()
      val shortestChildPath = mutableListOf<ChildNode>()
      var node: ReferencePathNode = retainedObjectNode
      while (node is ChildNode) {
        shortestChildPath.add(0, node)
        pathHeapObjects.add(0, graph.findObjectById(node.objectId))
        node = node.parent
      }
      val rootNode = node as RootNode
      pathHeapObjects.add(0, graph.findObjectById(rootNode.objectId))

      val leakTraceObjects = buildLeakTraceObjects(objectInspectors, pathHeapObjects)

      val referencePath = buildReferencePath(shortestChildPath, leakTraceObjects)

      val leakTrace = LeakTrace(
          gcRootType = GcRootType.fromGcRoot(rootNode.gcRoot),
          referencePath = referencePath,
          leakingObject = leakTraceObjects.last(),
          retainedHeapByteSize = retainedSizes?.get(index)
      )

      val firstLibraryLeakNode = if (rootNode is LibraryLeakNode) {
        rootNode
      } else {
        shortestChildPath.firstOrNull { it is LibraryLeakNode } as LibraryLeakNode?
      }

      if (firstLibraryLeakNode != null) {
        val matcher = firstLibraryLeakNode.matcher
        val signature: String = matcher.pattern.toString()
            .createSHA1Hash()
        libraryLeaksMap.getOrPut(signature) { matcher to mutableListOf() }
            .second += leakTrace
      } else {
        applicationLeaksMap.getOrPut(leakTrace.signature) { mutableListOf() } += leakTrace
      }
    }
    val applicationLeaks = applicationLeaksMap.map { (_, leakTraces) ->
      ApplicationLeak(leakTraces)
    }
    val libraryLeaks = libraryLeaksMap.map { (_, pair) ->
      val (matcher, leakTraces) = pair
      LibraryLeak(leakTraces, matcher.pattern, matcher.description)
    }
    return applicationLeaks to libraryLeaks
  }

  private fun buildLeakTraceObjects(
    objectInspectors: List<ObjectInspector>,
    pathHeapObjects: List<HeapObject>
  ): List<LeakTraceObject> {
    val leakReporters = pathHeapObjects.map { heapObject ->
      ObjectReporter(heapObject)
    }

    // Looping on inspectors first to get more cache hits.
    objectInspectors.forEach { inspector ->
      leakReporters.forEach { reporter ->
        inspector.inspect(reporter)
      }
    }

    val leakStatuses = computeLeakStatuses(leakReporters)

    return pathHeapObjects.mapIndexed { index, heapObject ->
      val leakReporter = leakReporters[index]
      val (leakStatus, leakStatusReason) = leakStatuses[index]
      val className = recordClassName(heapObject)

      val objectType = if (heapObject is HeapClass) {
        CLASS
      } else if (heapObject is HeapObjectArray || heapObject is HeapPrimitiveArray) {
        ARRAY
      } else {
        INSTANCE
      }

      LeakTraceObject(
          type = objectType,
          className = className,
          labels = leakReporter.labels,
          leakingStatus = leakStatus,
          leakingStatusReason = leakStatusReason
      )
    }
  }

  private fun buildReferencePath(
    shortestChildPath: List<ChildNode>,
    leakTraceObjects: List<LeakTraceObject>
  ): List<LeakTraceReference> {
    return shortestChildPath.mapIndexed { index, childNode ->
      LeakTraceReference(
          originObject = leakTraceObjects[index],
          referenceType = childNode.refFromParentType,
          referenceName = childNode.refFromParentName
      )
    }
  }

  private fun computeLeakStatuses(leakReporters: List<ObjectReporter>): List<Pair<LeakingStatus, String>> {
    val lastElementIndex = leakReporters.size - 1

    var lastNotLeakingElementIndex = -1
    var firstLeakingElementIndex = lastElementIndex

    val leakStatuses = ArrayList<Pair<LeakingStatus, String>>()

    for ((index, reporter) in leakReporters.withIndex()) {
      val resolvedStatusPair =
        resolveStatus(reporter, leakingWins = index == lastElementIndex).let { statusPair ->
          if (index == lastElementIndex) {
            // The last element should always be leaking.
            when (statusPair.first) {
              LEAKING -> statusPair
              UNKNOWN -> LEAKING to "This is the leaking object"
              NOT_LEAKING -> LEAKING to "This is the leaking object. Conflicts with ${statusPair.second}"
            }
          } else statusPair
        }

      leakStatuses.add(resolvedStatusPair)
      val (leakStatus, _) = resolvedStatusPair
      if (leakStatus == NOT_LEAKING) {
        lastNotLeakingElementIndex = index
        // Reset firstLeakingElementIndex so that we never have
        // firstLeakingElementIndex < lastNotLeakingElementIndex
        firstLeakingElementIndex = lastElementIndex
      } else if (leakStatus == LEAKING && firstLeakingElementIndex == lastElementIndex) {
        firstLeakingElementIndex = index
      }
    }

    val simpleClassNames = leakReporters.map { reporter ->
      recordClassName(reporter.heapObject).lastSegment('.')
    }

    for (i in 0 until lastNotLeakingElementIndex) {
      val (leakStatus, leakStatusReason) = leakStatuses[i]
      val nextNotLeakingIndex = generateSequence(i + 1) { index ->
        if (index < lastNotLeakingElementIndex) index + 1 else null
      }.first { index ->
        leakStatuses[index].first == NOT_LEAKING
      }

      // Element is forced to NOT_LEAKING
      val nextNotLeakingName = simpleClassNames[nextNotLeakingIndex]
      leakStatuses[i] = when (leakStatus) {
        UNKNOWN -> NOT_LEAKING to "$nextNotLeakingName↓ is not leaking"
        NOT_LEAKING -> NOT_LEAKING to "$nextNotLeakingName↓ is not leaking and $leakStatusReason"
        LEAKING -> NOT_LEAKING to "$nextNotLeakingName↓ is not leaking. Conflicts with $leakStatusReason"
      }
    }

    if (firstLeakingElementIndex < lastElementIndex - 1) {
      // We already know the status of firstLeakingElementIndex and lastElementIndex
      for (i in lastElementIndex - 1 downTo firstLeakingElementIndex + 1) {
        val (leakStatus, leakStatusReason) = leakStatuses[i]
        val previousLeakingIndex = generateSequence(i - 1) { index ->
          if (index > firstLeakingElementIndex) index - 1 else null
        }.first { index ->
          leakStatuses[index].first == LEAKING
        }

        // Element is forced to LEAKING
        val previousLeakingName = simpleClassNames[previousLeakingIndex]
        leakStatuses[i] = when (leakStatus) {
          UNKNOWN -> LEAKING to "$previousLeakingName↑ is leaking"
          LEAKING -> LEAKING to "$previousLeakingName↑ is leaking and $leakStatusReason"
          NOT_LEAKING -> throw IllegalStateException("Should never happen")
        }
      }
    }
    return leakStatuses
  }

  private fun resolveStatus(
    reporter: ObjectReporter,
    leakingWins: Boolean
  ): Pair<LeakingStatus, String> {
    var status = UNKNOWN
    var reason = ""
    if (reporter.notLeakingReasons.isNotEmpty()) {
      status = NOT_LEAKING
      reason = reporter.notLeakingReasons.joinToString(" and ")
    }
    val leakingReasons = reporter.leakingReasons
    if (leakingReasons.isNotEmpty()) {
      val winReasons = leakingReasons.joinToString(" and ")
      // Conflict
      if (status == NOT_LEAKING) {
        if (leakingWins) {
          status = LEAKING
          reason = "$winReasons. Conflicts with $reason"
        } else {
          reason += ". Conflicts with $winReasons"
        }
      } else {
        status = LEAKING
        reason = winReasons
      }
    }
    return status to reason
  }

  private fun recordClassName(
    heap: HeapObject
  ): String {
    return when (heap) {
      is HeapClass -> heap.name
      is HeapInstance -> heap.instanceClassName
      is HeapObjectArray -> heap.arrayClassName
      is HeapPrimitiveArray -> heap.arrayClassName
    }
  }

  private fun since(analysisStartNanoTime: Long): Long {
    return NANOSECONDS.toMillis(System.nanoTime() - analysisStartNanoTime)
  }
}
