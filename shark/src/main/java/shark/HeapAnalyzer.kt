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

import shark.OnAnalysisProgressListener.Step.BUILDING_LEAK_TRACES
import shark.OnAnalysisProgressListener.Step.COMPUTING_NATIVE_RETAINED_SIZE
import shark.OnAnalysisProgressListener.Step.COMPUTING_RETAINED_SIZE
import shark.OnAnalysisProgressListener.Step.FINDING_LEAKING_INSTANCES
import shark.OnAnalysisProgressListener.Step.PARSING_HEAP_DUMP
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.JniLocal
import shark.GcRoot.JniMonitor
import shark.GcRoot.MonitorUsed
import shark.GcRoot.NativeStack
import shark.GcRoot.ReferenceCleanup
import shark.GcRoot.StickyClass
import shark.GcRoot.ThreadBlock
import shark.GcRoot.ThreadObject
import shark.HeapAnalyzer.TrieNode.LeafNode
import shark.HeapAnalyzer.TrieNode.ParentNode
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.LeakNodeStatus.LEAKING
import shark.LeakNodeStatus.NOT_LEAKING
import shark.LeakNodeStatus.UNKNOWN
import shark.LeakTraceElement.Holder.ARRAY
import shark.LeakTraceElement.Holder.CLASS
import shark.LeakTraceElement.Holder.OBJECT
import shark.LeakTraceElement.Holder.THREAD
import shark.internal.ReferencePathNode
import shark.internal.ReferencePathNode.ChildNode
import shark.internal.ReferencePathNode.ChildNode.LibraryLeakNode
import shark.internal.ReferencePathNode.RootNode
import shark.internal.ShortestPathFinder
import shark.internal.ShortestPathFinder.Results
import shark.internal.hppc.LongLongScatterMap
import shark.internal.lastSegment
import java.io.File
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit.NANOSECONDS

/**
 * Analyzes heap dumps to look for leaks.
 */
class HeapAnalyzer constructor(
  private val listener: OnAnalysisProgressListener
) {

  /**
   * Searches the heap dump for leaking instances and then computes the shortest strong reference
   * path from those instances to the GC roots.
   */
  fun checkForLeaks(
    heapDumpFile: File,
    referenceMatchers: List<ReferenceMatcher> = emptyList(),
    computeRetainedHeapSize: Boolean = false,
    objectInspectors: List<ObjectInspector> = emptyList(),
    leakFinders: List<ObjectInspector> = objectInspectors
  ): HeapAnalysis {
    val analysisStartNanoTime = System.nanoTime()

    if (!heapDumpFile.exists()) {
      val exception = IllegalArgumentException("File does not exist: $heapDumpFile")
      return HeapAnalysisFailure(
          heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
          HeapAnalysisException(exception)
      )
    }

    try {
      listener.onAnalysisProgress(PARSING_HEAP_DUMP)
      Hprof.open(heapDumpFile).use { hprof ->
        val graph = HprofHeapGraph.indexHprof(hprof)
        listener.onAnalysisProgress(FINDING_LEAKING_INSTANCES)

        val leakingInstanceObjectIds = findLeakingInstances(graph, leakFinders)

        val (shortestPathsToLeakingInstances, dominatedInstances) =
          findShortestPaths(
              graph, referenceMatchers, leakingInstanceObjectIds, computeRetainedHeapSize
          )

        val retainedSizes = if (computeRetainedHeapSize) {
          computeRetainedSizes(graph, shortestPathsToLeakingInstances, dominatedInstances)
        } else {
          null
        }

        val (applicationLeaks, libraryLeaks) = buildLeakTraces(
            objectInspectors, shortestPathsToLeakingInstances, graph, retainedSizes
        )

        return HeapAnalysisSuccess(
            heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
            applicationLeaks, libraryLeaks
        )
      }
    } catch (exception: Throwable) {
      return HeapAnalysisFailure(
          heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
          HeapAnalysisException(exception)
      )
    }
  }

  private fun findLeakingInstances(
    graph: HeapGraph,
    objectInspectors: List<ObjectInspector>
  ): Set<Long> {
    return graph.objects
        .filter { objectRecord ->
          val reporter = ObjectReporter(objectRecord)
          objectInspectors.forEach { inspector ->
            inspector.inspect(reporter)
          }
          reporter.leakingStatuses.isNotEmpty()
        }
        .map { it.objectId }
        .toSet()
  }

  private fun findShortestPaths(
    graph: HeapGraph,
    referenceMatchers: List<ReferenceMatcher>,
    leakingInstanceObjectIds: Set<Long>,
    computeDominators: Boolean
  ): Results {
    val pathFinder = ShortestPathFinder()
    return pathFinder.findPaths(
        graph, referenceMatchers, leakingInstanceObjectIds, computeDominators, listener
    )
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
        path.add(0, leakNode.instance)
        leakNode = leakNode.parent
      }
      path.add(0, leakNode.instance)
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

  private fun computeRetainedSizes(
    graph: HeapGraph,
    results: List<ReferencePathNode>,
    dominatedInstances: LongLongScatterMap
  ): List<Int> {
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
    results.forEach { pathNode ->
      val leakingInstanceObjectId = pathNode.instance
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
      results.map { it.instance }
          .forEach { leakingInstanceId ->
            val dominator = dominatedInstances[leakingInstanceId]
            if (dominator != null) {
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
    return results.map { pathNode ->
      sizeByDominator[pathNode.instance]!!
    }
  }

  private fun buildLeakTraces(
    objectInspectors: List<ObjectInspector>,
    shortestPathsToLeakingInstances: List<ReferencePathNode>,
    graph: HeapGraph,
    retainedSizes: List<Int>?
  ): Pair<List<ApplicationLeak>, List<LibraryLeak>> {
    listener.onAnalysisProgress(BUILDING_LEAK_TRACES)

    val applicationLeaks = mutableListOf<ApplicationLeak>()
    val libraryLeaks = mutableListOf<LibraryLeak>()

    val deduplicatedPaths = deduplicateShortestPaths(shortestPathsToLeakingInstances)

    deduplicatedPaths.forEachIndexed { index, pathNode ->
      val shortestChildPath = mutableListOf<ChildNode>()

      var node: ReferencePathNode = pathNode
      while (node is ChildNode) {
        shortestChildPath.add(0, node)
        node = node.parent
      }
      val rootNode = node as RootNode

      val leakTrace =
        buildLeakTrace(graph, objectInspectors, rootNode, shortestChildPath)

      val className =
        recordClassName(graph.findObjectById(pathNode.instance))

      val firstLibraryLeakNode =
        shortestChildPath.firstOrNull { it is LibraryLeakNode } as LibraryLeakNode?

      if (firstLibraryLeakNode != null) {
        val matcher = firstLibraryLeakNode.matcher
        libraryLeaks += LibraryLeak(
            className, leakTrace, retainedSizes?.get(index), matcher.pattern, matcher.description
        )
      } else {
        applicationLeaks += ApplicationLeak(className, leakTrace, retainedSizes?.get(index))
      }
    }
    return applicationLeaks to libraryLeaks
  }

  private fun buildLeakTrace(
    graph: HeapGraph,
    objectInspectors: List<ObjectInspector>,
    rootNode: RootNode,
    shortestChildPath: List<ChildNode>
  ): LeakTrace {
    val shortestPath = shortestChildPath.toMutableList<ReferencePathNode>()
    shortestPath.add(0, rootNode)

    val leakReporters = shortestPath.map {
      ObjectReporter(graph.findObjectById(it.instance))
    }

    // Looping on inspectors first to get more cache hits.
    objectInspectors.forEach { inspector ->
      leakReporters.forEach { reporter ->
        inspector.inspect(reporter)
      }
    }

    val leakStatuses = computeLeakStatuses(rootNode, leakReporters)

    val elements = shortestPath.mapIndexed { index, pathNode ->
      val leakReporter = leakReporters[index]
      val leakStatus = leakStatuses[index]
      val reference =
        if (index < shortestPath.lastIndex) (shortestPath[index + 1] as ChildNode).referenceFromParent else null
      buildLeakElement(graph, pathNode, reference, leakReporter.labels, leakStatus)
    }
    return LeakTrace(elements)
  }

  private fun computeLeakStatuses(
    rootNode: RootNode,
    leakReporters: List<ObjectReporter>
  ): List<LeakNodeStatusAndReason> {
    val lastElementIndex = leakReporters.size - 1

    val rootNodeReporter = leakReporters[0]

    rootNodeReporter.addLabel(
        "GC Root: " + when (rootNode.gcRoot) {
          is ThreadObject -> "Thread object"
          is JniGlobal -> "Global variable in native code"
          is JniLocal -> "Local variable in native code"
          is JavaFrame -> "Java local variable"
          is NativeStack -> "Input or output parameters in native code"
          is StickyClass -> "System class"
          is ThreadBlock -> "Thread block"
          is MonitorUsed -> "Monitor (anything that called the wait() or notify() methods, or that is synchronized.)"
          is ReferenceCleanup -> "Reference cleanup"
          is JniMonitor -> "Root JNI monitor"
          else -> throw IllegalStateException("Unexpected gc root ${rootNode.gcRoot}")
        }
    )

    var lastNotLeakingElementIndex = 0
    var firstLeakingElementIndex = lastElementIndex

    val leakStatuses = ArrayList<LeakNodeStatusAndReason>()

    for ((index, reporter) in leakReporters.withIndex()) {
      val leakStatus = resolveStatus(reporter)
      leakStatuses.add(leakStatus)
      if (leakStatus.status == NOT_LEAKING) {
        lastNotLeakingElementIndex = index
        // Reset firstLeakingElementIndex so that we never have
        // firstLeakingElementIndex < lastNotLeakingElementIndex
        firstLeakingElementIndex = lastElementIndex
      } else if (firstLeakingElementIndex == lastElementIndex && leakStatus.status == LEAKING) {
        firstLeakingElementIndex = index
      }
    }

    val simpleClassNames = leakReporters.map { reporter ->
      recordClassName(reporter.heapObject).lastSegment('.')
    }

    // First and last are always known.
    for (i in 0..lastElementIndex) {
      val leakStatus = leakStatuses[i]
      if (i < lastNotLeakingElementIndex) {
        val nextNotLeakingName = simpleClassNames[i + 1]
        leakStatuses[i] = when (leakStatus.status) {
          UNKNOWN -> LeakNodeStatus.notLeaking("$nextNotLeakingName↓ is not leaking")
          NOT_LEAKING -> LeakNodeStatus.notLeaking(
              "$nextNotLeakingName↓ is not leaking and ${leakStatus.reason}"
          )
          LEAKING -> LeakNodeStatus.notLeaking(
              "$nextNotLeakingName↓ is not leaking. Conflicts with ${leakStatus.reason}"
          )
        }
      } else if (i > firstLeakingElementIndex) {
        val previousLeakingName = simpleClassNames[i - 1]
        leakStatuses[i] = LeakNodeStatus.leaking("$previousLeakingName↑ is leaking")

        leakStatuses[i] = when (leakStatus.status) {
          UNKNOWN -> LeakNodeStatus.leaking("$previousLeakingName↑ is leaking")
          LEAKING -> LeakNodeStatus.leaking(
              "$previousLeakingName↑ is leaking and ${leakStatus.reason}"
          )
          NOT_LEAKING -> throw IllegalStateException("Should never happen")
        }
      }
    }
    return leakStatuses
  }

  private fun resolveStatus(
    reporter: ObjectReporter
  ): LeakNodeStatusAndReason {
    // NOT_LEAKING always wins over LEAKING
    var current = LeakNodeStatus.unknown()
    for (statusAndReason in reporter.leakNodeStatuses) {
      current = when {
        current.status == UNKNOWN -> statusAndReason
        current.status == LEAKING && statusAndReason.status == LEAKING -> {
          LeakNodeStatus.leaking("${current.reason} and ${statusAndReason.reason}")
        }
        current.status == NOT_LEAKING && statusAndReason.status == NOT_LEAKING -> {
          LeakNodeStatus.notLeaking("${current.reason} and ${statusAndReason.reason}")
        }
        current.status == NOT_LEAKING && statusAndReason.status == LEAKING -> {
          LeakNodeStatus.notLeaking(
              "${current.reason}. Conflicts with ${statusAndReason.reason}"
          )
        }
        current.status == LEAKING && statusAndReason.status == NOT_LEAKING -> {
          LeakNodeStatus.notLeaking(
              "${statusAndReason.reason}. Conflicts with ${current.reason}"
          )
        }
        else -> throw IllegalStateException(
            "Should never happen ${current.status} ${statusAndReason.reason}"
        )
      }
    }
    return current
  }

  private fun buildLeakElement(
    graph: HeapGraph,
    node: ReferencePathNode,
    reference: LeakReference?,
    labels: List<String>,
    leakStatus: LeakNodeStatusAndReason
  ): LeakTraceElement {
    val objectId = node.instance

    val graphRecord = graph.findObjectById(objectId)

    val className = recordClassName(graphRecord)

    val holderType = if (graphRecord is HeapClass) {
      CLASS
    } else if (graphRecord is HeapObjectArray || graphRecord is HeapPrimitiveArray) {
      ARRAY
    } else {
      val instanceRecord = graphRecord.asInstance!!
      if (instanceRecord.instanceClass.classHierarchy.any { it.name == Thread::class.java.name }) {
        THREAD
      } else {
        OBJECT
      }
    }
    return LeakTraceElement(reference, holderType, className, labels, leakStatus)
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
