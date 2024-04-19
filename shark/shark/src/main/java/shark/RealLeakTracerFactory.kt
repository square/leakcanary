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
import shark.internal.ReferencePathNode
import shark.internal.ReferencePathNode.ChildNode
import shark.internal.ReferencePathNode.RootNode
import shark.internal.ShallowSizeCalculator
import shark.internal.createSHA1Hash
import shark.internal.lastSegment
import java.util.ArrayList
import shark.RealLeakTracerFactory.Event.StartedBuildingLeakTraces
import shark.RealLeakTracerFactory.Event.StartedComputingJavaHeapRetainedSize
import shark.RealLeakTracerFactory.Event.StartedComputingNativeRetainedSize
import shark.RealLeakTracerFactory.Event.StartedInspectingObjects
import shark.RealLeakTracerFactory.TrieNode.LeafNode
import shark.RealLeakTracerFactory.TrieNode.ParentNode
import shark.internal.ReferencePathNode.RootNode.LibraryLeakRootNode

// TODO kdoc
// TODO better name than "real"
// TODO Think about making this easier to build up. Having good defaults, or a builder of sorts.
//  objectInspectors and referenceMatchers can default to empty list and listener be a no-op
class RealLeakTracerFactory constructor(
  private val shortestPathFinderFactory: ShortestPathFinder.Factory,
  private val objectInspectors: List<ObjectInspector>,
  private val listener: Event.Listener
): LeakTracer.Factory {

  // TODO Enum or sealed? class makes it possible to report progress. Enum
  // provides ordering of events.
  sealed interface Event {
    object StartedBuildingLeakTraces : Event
    object StartedInspectingObjects : Event
    @Deprecated("Event not sent anymore")
    object StartedComputingNativeRetainedSize: Event
    object StartedComputingJavaHeapRetainedSize: Event

    fun interface Listener {
      fun onEvent(event: Event)
    }
  }

  override fun createFor(heapGraph: HeapGraph): LeakTracer {
    // TODO Remove the listener and replace that by specific events
    //  Also for each event some notion of progress? Should that be configurable?
    //  We should be able to tell the total number of objects so we'll know when we've
    //  traversed the whole graph.
    //  referenceMatchers are only needed for the NativeGlobalVariablePattern, which is related
    //  to GC roots
    return LeakTracer { objectIds->
      val helpers = FindLeakInput(
        heapGraph,
        shortestPathFinderFactory.createFor(heapGraph),
        objectInspectors,
      )
      helpers.findLeaks(objectIds)
    }
  }


  private class FindLeakInput(
    val graph: HeapGraph,
    val shortestPathFinder: ShortestPathFinder,
    val objectInspectors: List<ObjectInspector>,
  )

  private fun FindLeakInput.findLeaks(leakingObjectIds: Set<Long>): LeaksAndUnreachableObjects {
    val pathFindingResults =
      shortestPathFinder.findShortestPathsFromGcRoots(leakingObjectIds)

    val unreachableObjects = findUnreachableObjects(pathFindingResults, leakingObjectIds)

    val shortestPaths =
      deduplicateShortestPaths(pathFindingResults.pathsToLeakingObjects)

    val inspectedObjectsByPath = inspectObjects(shortestPaths)

    val retainedSizes =
      if (pathFindingResults.dominatorTree != null) {
        computeRetainedSizes(inspectedObjectsByPath, pathFindingResults.dominatorTree)
      } else {
        null
      }
    val (applicationLeaks, libraryLeaks) = buildLeakTraces(
      shortestPaths, inspectedObjectsByPath, retainedSizes
    )
    return LeaksAndUnreachableObjects(applicationLeaks, libraryLeaks, unreachableObjects)
  }

  private fun FindLeakInput.findUnreachableObjects(
    pathFindingResults: PathFindingResults,
    leakingObjectIds: Set<Long>
  ): List<LeakTraceObject> {
    val reachableLeakingObjectIds =
      pathFindingResults.pathsToLeakingObjects.map { it.objectId }.toSet()

    val unreachableLeakingObjectIds = leakingObjectIds - reachableLeakingObjectIds

    val unreachableObjectReporters = unreachableLeakingObjectIds.map { objectId ->
      ObjectReporter(heapObject = graph.findObjectById(objectId))
    }

    objectInspectors.forEach { inspector ->
      unreachableObjectReporters.forEach { reporter ->
        inspector.inspect(reporter)
      }
    }

    val unreachableInspectedObjects = unreachableObjectReporters.map { reporter ->
      val reason = resolveStatus(reporter, leakingWins = true).let { (status, reason) ->
        when (status) {
          LEAKING -> reason
          UNKNOWN -> "This is a leaking object"
          NOT_LEAKING -> "This is a leaking object. Conflicts with $reason"
        }
      }
      InspectedObject(
        reporter.heapObject, LEAKING, reason, reporter.labels
      )
    }

    return buildLeakTraceObjects(unreachableInspectedObjects, null)
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
      val childNode = parentNode.children[objectId] ?: run {
        val newChildNode = ParentNode(objectId)
        parentNode.children[objectId] = newChildNode
        newChildNode
      }
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
    childPath: List<ChildNode>
  ) {

    val childPathWithDetails = childPath.map { it to it.lazyDetailsResolver.resolve() }

    fun firstLibraryLeakMatcher(): LibraryLeakReferenceMatcher? {
      if (root is LibraryLeakRootNode) {
        return root.matcher
      }
      return childPathWithDetails.map { it.second.matchedLibraryLeak }.firstOrNull { it != null }
    }

    fun asNodesWithMatchers(): List<Pair<ReferencePathNode, LibraryLeakReferenceMatcher?>> {
      val rootMatcher = if (root is LibraryLeakRootNode) {
        root.matcher
      } else null
      val childPathWithMatchers =
        childPathWithDetails.map { it.first to it.second.matchedLibraryLeak }
      return listOf(root to rootMatcher) + childPathWithMatchers
    }
  }

  private fun FindLeakInput.buildLeakTraces(
    shortestPaths: List<ShortestPath>,
    inspectedObjectsByPath: List<List<InspectedObject>>,
    retainedSizes: Map<Long, Pair<Int, Int>>?
  ): Pair<List<ApplicationLeak>, List<LibraryLeak>> {
    listener.onEvent(StartedBuildingLeakTraces)

    val applicationLeaksMap = mutableMapOf<String, MutableList<LeakTrace>>()
    val libraryLeaksMap =
      mutableMapOf<String, Pair<LibraryLeakReferenceMatcher, MutableList<LeakTrace>>>()

    shortestPaths.forEachIndexed { pathIndex, shortestPath ->
      val inspectedObjects = inspectedObjectsByPath[pathIndex]

      val leakTraceObjects = buildLeakTraceObjects(inspectedObjects, retainedSizes)

      val referencePath = buildReferencePath(shortestPath, leakTraceObjects)

      val leakTrace = LeakTrace(
        gcRootType = GcRootType.fromGcRoot(shortestPath.root.gcRoot),
        referencePath = referencePath,
        leakingObject = leakTraceObjects.last()
      )

      val firstLibraryLeakMatcher = shortestPath.firstLibraryLeakMatcher()
      if (firstLibraryLeakMatcher != null) {
        val signature: String = firstLibraryLeakMatcher.pattern.toString()
          .createSHA1Hash()
        libraryLeaksMap.getOrPut(signature) { firstLibraryLeakMatcher to mutableListOf() }
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
    listener.onEvent(StartedInspectingObjects)

    val leakReportersByPath = shortestPaths.map { path ->
      val pathList = path.asNodesWithMatchers()
      pathList
        .mapIndexed { index, (node, _) ->
          val reporter = ObjectReporter(heapObject = graph.findObjectById(node.objectId))
          if (index + 1 < pathList.size) {
            val (_, nextMatcher) = pathList[index + 1]
            if (nextMatcher != null) {
              reporter.labels += "Library leak match: ${nextMatcher.pattern}"
            }
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
  ): Map<Long, Pair<Int, Int>> {
    val nodeObjectIds = inspectedObjectsByPath.flatMap { inspectedObjects ->
      // TODO Stop at the first leaking object
      inspectedObjects.filter { it.leakingStatus == UNKNOWN || it.leakingStatus == LEAKING }
        .map { it.heapObject.objectId }
    }.toSet()
    listener.onEvent(StartedComputingJavaHeapRetainedSize)
    val objectSizeCalculator = AndroidObjectSizeCalculator(graph)
    return dominatorTree.computeRetainedSizes(nodeObjectIds, objectSizeCalculator)
  }

  private fun buildLeakTraceObjects(
    inspectedObjects: List<InspectedObject>,
    retainedSizes: Map<Long, Pair<Int, Int>>?
  ): List<LeakTraceObject> {
    return inspectedObjects.map { inspectedObject ->
      val heapObject = inspectedObject.heapObject
      val className = recordClassName(heapObject)

      val objectType = when (heapObject) {
        is HeapClass -> CLASS
        is HeapObjectArray, is HeapPrimitiveArray -> ARRAY
        else -> INSTANCE
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
    shortestPath: ShortestPath,
    leakTraceObjects: List<LeakTraceObject>
  ): List<LeakTraceReference> {
    return shortestPath.childPathWithDetails.mapIndexed { index, (_, details) ->
      LeakTraceReference(
        originObject = leakTraceObjects[index],
        referenceType = when (details.locationType) {
          ReferenceLocationType.INSTANCE_FIELD -> LeakTraceReference.ReferenceType.INSTANCE_FIELD
          ReferenceLocationType.STATIC_FIELD -> LeakTraceReference.ReferenceType.STATIC_FIELD
          ReferenceLocationType.LOCAL -> LeakTraceReference.ReferenceType.LOCAL
          ReferenceLocationType.ARRAY_ENTRY -> LeakTraceReference.ReferenceType.ARRAY_ENTRY
        },
        owningClassName = graph.findObjectById(details.locationClassObjectId).asClass!!.name,
        referenceName = details.name
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
}
