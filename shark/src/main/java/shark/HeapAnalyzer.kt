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
import shark.HprofHeapGraph.Companion.openHeapGraph
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
import shark.OnAnalysisProgressListener.Step.INSPECTING_OBJECTS
import shark.OnAnalysisProgressListener.Step.PARSING_HEAP_DUMP
import shark.internal.AndroidNativeSizeMapper
import shark.internal.DominatorTree
import shark.internal.PathFinder
import shark.internal.ReferencePathNode
import shark.internal.ReferencePathNode.ChildNode
import shark.internal.ReferencePathNode.LibraryLeakNode
import shark.internal.ReferencePathNode.RootNode
import shark.internal.ShallowSizeCalculator
import shark.internal.createSHA1Hash
import shark.internal.lastSegment
import java.io.File
import java.util.ArrayList
import java.util.concurrent.TimeUnit.NANOSECONDS

/**
 * Analyzes heap dumps to look for leaks.
 */
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
          heapDumpFile = heapDumpFile,
          createdAtTimeMillis = System.currentTimeMillis(),
          analysisDurationMillis = since(analysisStartNanoTime),
          exception = HeapAnalysisException(exception)
      )
    }

    return try {
      listener.onAnalysisProgress(PARSING_HEAP_DUMP)
      val sourceProvider = ConstantMemoryMetricsDualSourceProvider(FileSourceProvider(heapDumpFile))
      sourceProvider.openHeapGraph(proguardMapping).use { graph ->
        val helpers =
          FindLeakInput(graph, referenceMatchers, computeRetainedHeapSize, objectInspectors)
        val result = helpers.analyzeGraph(
            metadataExtractor, leakingObjectFinder, heapDumpFile, analysisStartNanoTime
        )
        val lruCacheStats = (graph as HprofHeapGraph).lruCacheStats()
        val randomAccessStats =
          "RandomAccess[" +
              "bytes=${sourceProvider.randomAccessByteReads}," +
              "reads=${sourceProvider.randomAccessReadCount}," +
              "travel=${sourceProvider.randomAccessByteTravel}," +
              "range=${sourceProvider.byteTravelRange}," +
              "size=${heapDumpFile.length()}" +
              "]"
        val stats = "$lruCacheStats $randomAccessStats"
        result.copy(metadata = result.metadata + ("Stats" to stats))
      }
    } catch (exception: Throwable) {
      HeapAnalysisFailure(
          heapDumpFile = heapDumpFile,
          createdAtTimeMillis = System.currentTimeMillis(),
          analysisDurationMillis = since(analysisStartNanoTime),
          exception = HeapAnalysisException(exception)
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
          heapDumpFile = heapDumpFile,
          createdAtTimeMillis = System.currentTimeMillis(),
          analysisDurationMillis = since(analysisStartNanoTime),
          exception = HeapAnalysisException(exception)
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

    val shortestPaths =
      deduplicateShortestPaths(pathFindingResults.pathsToLeakingObjects)

    val inspectedObjectsByPath = inspectObjects(shortestPaths)

    val retainedSizes =
      if (pathFindingResults.dominatorTree != null) {
        computeRetainedSizes(inspectedObjectsByPath, pathFindingResults.dominatorTree)
      } else {
        null
      }
    return buildLeakTraces(shortestPaths, inspectedObjectsByPath, retainedSizes)
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

  private fun deduplicateShortestPaths(
    inputPathResults: List<ReferencePathNode>
  ): List<ShortestPath> {
    val rootTrieNode = ParentNode(0)

    inputPathResults.forEach { pathNode ->
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

    if (outputPathResults.size != inputPathResults.size) {
      SharkLog.d {
        "Found ${inputPathResults.size} paths to retained objects," +
            " down to ${outputPathResults.size} after removing duplicated paths"
      }
    } else {
      SharkLog.d { "Found ${outputPathResults.size} paths to retained objects" }
    }

    return outputPathResults.map { retainedObjectNode ->
      val shortestChildPath = mutableListOf<ChildNode>()
      var node = retainedObjectNode
      while (node is ChildNode) {
        shortestChildPath.add(0, node)
        node = node.parent
      }
      val rootNode = node as RootNode
      ShortestPath(rootNode, shortestChildPath)
    }
  }

  private fun updateTrie(
    pathNode: ReferencePathNode,
    path: List<Long>,
    pathIndex: Int,
    parentNode: ParentNode
  ) {
    val objectId = path[pathIndex]
    if (pathIndex == path.lastIndex) {
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

  internal class ShortestPath(
    val root: RootNode,
    val childPath: List<ChildNode>
  ) {
    fun asList() = listOf(root) + childPath
  }

  private fun FindLeakInput.buildLeakTraces(
    shortestPaths: List<ShortestPath>,
    inspectedObjectsByPath: List<List<InspectedObject>>,
    retainedSizes: Map<Long, Pair<Int, Int>>?
  ): Pair<List<ApplicationLeak>, List<LibraryLeak>> {
    listener.onAnalysisProgress(BUILDING_LEAK_TRACES)

    val applicationLeaksMap = mutableMapOf<String, MutableList<LeakTrace>>()
    val libraryLeaksMap =
      mutableMapOf<String, Pair<LibraryLeakReferenceMatcher, MutableList<LeakTrace>>>()

    shortestPaths.forEachIndexed { pathIndex, shortestPath ->
      val inspectedObjects = inspectedObjectsByPath[pathIndex]

      val leakTraceObjects = buildLeakTraceObjects(inspectedObjects, retainedSizes)

      val referencePath = buildReferencePath(shortestPath.childPath, leakTraceObjects)

      val leakTrace = LeakTrace(
          gcRootType = GcRootType.fromGcRoot(shortestPath.root.gcRoot),
          referencePath = referencePath,
          leakingObject = leakTraceObjects.last()
      )

      val firstLibraryLeakNode = if (shortestPath.root is LibraryLeakNode) {
        shortestPath.root
      } else {
        shortestPath.childPath.firstOrNull { it is LibraryLeakNode } as LibraryLeakNode?
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

  private fun FindLeakInput.inspectObjects(shortestPaths: List<ShortestPath>): List<List<InspectedObject>> {
    listener.onAnalysisProgress(INSPECTING_OBJECTS)

    val leakReportersByPath = shortestPaths.map { path ->
      val pathList = path.asList()
      pathList
          .mapIndexed { index, node ->
            val reporter = ObjectReporter(heapObject = graph.findObjectById(node.objectId))
            val nextNode = if (index + 1 < pathList.size) pathList[index + 1] else null
            if (nextNode is LibraryLeakNode) {
              reporter.labels += "Library leak match: ${nextNode.matcher.pattern}"
            }
            reporter
          }
    }

    objectInspectors.forEach { inspector ->
      leakReportersByPath.forEach { leakReporters ->
        leakReporters.forEach { reporter ->
          inspector.inspect(reporter)
        }
      }
    }

    return leakReportersByPath.map { leakReporters ->
      computeLeakStatuses(leakReporters)
    }
  }

  private fun FindLeakInput.computeRetainedSizes(
    inspectedObjectsByPath: List<List<InspectedObject>>,
    dominatorTree: DominatorTree
  ): Map<Long, Pair<Int, Int>>? {
    val nodeObjectIds = inspectedObjectsByPath.flatMap { inspectedObjects ->
      inspectedObjects.filter { it.leakingStatus == UNKNOWN || it.leakingStatus == LEAKING }
          .map { it.heapObject.objectId }
    }.toSet()
    listener.onAnalysisProgress(COMPUTING_NATIVE_RETAINED_SIZE)
    val nativeSizeMapper = AndroidNativeSizeMapper(graph)
    val nativeSizes = nativeSizeMapper.mapNativeSizes()
    listener.onAnalysisProgress(COMPUTING_RETAINED_SIZE)
    val shallowSizeCalculator = ShallowSizeCalculator(graph)
    return dominatorTree.computeRetainedSizes(nodeObjectIds) { objectId ->
      val nativeSize = nativeSizes[objectId] ?: 0
      val shallowSize = shallowSizeCalculator.computeShallowSize(objectId)
      nativeSize + shallowSize
    }
  }

  private fun buildLeakTraceObjects(
    inspectedObjects: List<InspectedObject>,
    retainedSizes: Map<Long, Pair<Int, Int>>?
  ): List<LeakTraceObject> {
    return inspectedObjects.map { inspectedObject ->
      val heapObject = inspectedObject.heapObject
      val className = recordClassName(heapObject)

      val objectType = if (heapObject is HeapClass) {
        CLASS
      } else if (heapObject is HeapObjectArray || heapObject is HeapPrimitiveArray) {
        ARRAY
      } else {
        INSTANCE
      }

      val retainedSizeAndObjectCount = retainedSizes?.get(inspectedObject.heapObject.objectId)

      LeakTraceObject(
          type = objectType,
          className = className,
          labels = inspectedObject.labels,
          leakingStatus = inspectedObject.leakingStatus,
          leakingStatusReason = inspectedObject.leakingStatusReason,
          retainedHeapByteSize = retainedSizeAndObjectCount?.first,
          retainedObjectCount = retainedSizeAndObjectCount?.second
      )
    }
  }

  private fun FindLeakInput.buildReferencePath(
    shortestChildPath: List<ChildNode>,
    leakTraceObjects: List<LeakTraceObject>
  ): List<LeakTraceReference> {
    return shortestChildPath.mapIndexed { index, childNode ->
      LeakTraceReference(
          originObject = leakTraceObjects[index],
          referenceType = childNode.refFromParentType,
          owningClassName = if (childNode.owningClassId != 0L) {
            graph.findObjectById(childNode.owningClassId).asClass!!.name
          } else {
            leakTraceObjects[index].className
          },
          referenceName = childNode.refFromParentName
      )
    }
  }

  internal class InspectedObject(
    val heapObject: HeapObject,
    val leakingStatus: LeakingStatus,
    val leakingStatusReason: String,
    val labels: MutableSet<String>
  )

  private fun computeLeakStatuses(leakReporters: List<ObjectReporter>): List<InspectedObject> {
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

    return leakReporters.mapIndexed { index, objectReporter ->
      val (leakingStatus, leakingStatusReason) = leakStatuses[index]
      InspectedObject(
          objectReporter.heapObject, leakingStatus, leakingStatusReason, objectReporter.labels
      )
    }
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
