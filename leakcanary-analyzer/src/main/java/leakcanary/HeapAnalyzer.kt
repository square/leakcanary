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
package leakcanary

import leakcanary.AnalyzerProgressListener.Step.BUILDING_LEAK_TRACES
import leakcanary.AnalyzerProgressListener.Step.COMPUTING_NATIVE_RETAINED_SIZE
import leakcanary.AnalyzerProgressListener.Step.COMPUTING_RETAINED_SIZE
import leakcanary.AnalyzerProgressListener.Step.FINDING_WATCHED_REFERENCES
import leakcanary.AnalyzerProgressListener.Step.READING_HEAP_DUMP_FILE
import leakcanary.AnalyzerProgressListener.Step.SCANNING_HEAP_DUMP
import leakcanary.GcRoot.JavaFrame
import leakcanary.GcRoot.JniGlobal
import leakcanary.GcRoot.JniLocal
import leakcanary.GcRoot.JniMonitor
import leakcanary.GcRoot.MonitorUsed
import leakcanary.GcRoot.NativeStack
import leakcanary.GcRoot.ReferenceCleanup
import leakcanary.GcRoot.StickyClass
import leakcanary.GcRoot.ThreadBlock
import leakcanary.GcRoot.ThreadObject
import leakcanary.GraphObjectRecord.GraphClassRecord
import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.GraphObjectRecord.GraphObjectArrayRecord
import leakcanary.GraphObjectRecord.GraphPrimitiveArrayRecord
import leakcanary.HeapAnalyzer.TrieNode.LeafNode
import leakcanary.HeapAnalyzer.TrieNode.ParentNode
import leakcanary.LeakNode.ChildNode
import leakcanary.LeakNode.RootNode
import leakcanary.LeakNodeStatus.LEAKING
import leakcanary.LeakNodeStatus.NOT_LEAKING
import leakcanary.LeakNodeStatus.UNKNOWN
import leakcanary.LeakTraceElement.Holder.ARRAY
import leakcanary.LeakTraceElement.Holder.CLASS
import leakcanary.LeakTraceElement.Holder.OBJECT
import leakcanary.LeakTraceElement.Holder.THREAD
import leakcanary.internal.GcRootRecordListener
import leakcanary.internal.ShortestPathFinder
import leakcanary.internal.ShortestPathFinder.Result
import leakcanary.internal.ShortestPathFinder.Results
import leakcanary.internal.hppc.LongLongScatterMap
import leakcanary.internal.lastSegment
import java.io.File
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit.NANOSECONDS

/**
 * Analyzes heap dumps to look for leaks.
 */
class HeapAnalyzer constructor(
  private val listener: AnalyzerProgressListener
) {

  /**
   * Searches the heap dump for a [KeyedWeakReference] instance with the corresponding key,
   * and then computes the shortest strong reference path from that instance to the GC roots.
   */
  fun checkForLeaks(
    heapDumpFile: File,
    exclusions: List<Exclusion> = emptyList(),
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

    listener.onProgressUpdate(READING_HEAP_DUMP_FILE)

    try {
      listener.onProgressUpdate(SCANNING_HEAP_DUMP)

      val gcRootRecordListener = GcRootRecordListener()
      val (graph, hprofCloseable) = HprofGraph.readHprof(heapDumpFile, gcRootRecordListener)

      hprofCloseable.use {
        val analysisResults = mutableListOf<LeakingInstance>()
        listener.onProgressUpdate(FINDING_WATCHED_REFERENCES)

        val leakingInstanceObjectIds = findLeakingInstances(graph, leakFinders)

        val (pathResults, dominatedInstances) =
          findShortestPaths(
              graph, exclusions, leakingInstanceObjectIds, gcRootRecordListener.gcRoots,
              computeRetainedHeapSize
          )

        val retainedSizes = if (computeRetainedHeapSize) {
          computeRetainedSizes(graph, pathResults, dominatedInstances)
        } else {
          null
        }

        buildLeakTraces(objectInspectors, pathResults, graph, analysisResults, retainedSizes)

        return HeapAnalysisSuccess(
            heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
            analysisResults
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
    graph: HprofGraph,
    objectInspectors: List<ObjectInspector>
  ): Set<Long> {
    return graph.objectSequence()
        .filter { objectRecord ->
          val reporter = ObjectReporter(objectRecord)
          objectInspectors.forEach { inspector ->
            inspector.inspect(graph, reporter)
          }
          reporter.leakingStatuses.isNotEmpty()
        }
        .map { it.objectId }
        .toSet()
  }

  private fun findShortestPaths(
    graph: HprofGraph,
    exclusions: List<Exclusion>,
    leakingInstanceObjectIds: Set<Long>,
    gcRootIds: MutableList<GcRoot>,
    computeDominators: Boolean
  ): Results {
    val pathFinder = ShortestPathFinder()
    return pathFinder.findPaths(
        graph, exclusions, leakingInstanceObjectIds, gcRootIds, computeDominators, listener
    )
  }

  internal sealed class TrieNode {
    abstract val objectId: Long

    class ParentNode(override val objectId: Long) : TrieNode() {
      val children = mutableMapOf<Long, TrieNode>()
    }

    class LeafNode(
      override val objectId: Long,
      val result: Result
    ) : TrieNode()

  }

  private fun deduplicateResults(inputPathResults: List<Result>): List<Result> {
    val rootTrieNode = ParentNode(0)

    for (result in inputPathResults) {
      // Go through the linked list of nodes and build the reverse list of instances from
      // root to leaking.
      val path = mutableListOf<Long>()
      var leakNode: LeakNode = result.leakingNode
      while (leakNode is ChildNode) {
        path.add(0, leakNode.instance)
        leakNode = leakNode.parent
      }
      path.add(0, leakNode.instance)
      updateTrie(result, path, 0, rootTrieNode)
    }

    val outputPathResults = mutableListOf<Result>()
    findResultsInTrie(rootTrieNode, outputPathResults)
    return outputPathResults
  }

  private fun updateTrie(
    result: Result,
    path: List<Long>,
    pathIndex: Int,
    parentNode: ParentNode
  ) {
    val objectId = path[pathIndex]
    if (pathIndex == path.lastIndex) {
      // Replace any preexisting children, this is shorter.
      parentNode.children[objectId] = LeafNode(objectId, result)
    } else {
      val childNode = parentNode.children[objectId] ?: {
        val newChildNode = ParentNode(objectId)
        parentNode.children[objectId] = newChildNode
        newChildNode
      }()
      if (childNode is ParentNode) {
        updateTrie(result, path, pathIndex + 1, childNode)
      }
    }
  }

  private fun findResultsInTrie(
    parentNode: ParentNode,
    outputPathResults: MutableList<Result>
  ) {
    parentNode.children.values.forEach { childNode ->
      when (childNode) {
        is ParentNode -> {
          findResultsInTrie(childNode, outputPathResults)
        }
        is LeafNode -> {
          outputPathResults += childNode.result
        }
      }
    }
  }

  private fun computeRetainedSizes(
    graph: HprofGraph,
    results: List<Result>,
    dominatedInstances: LongLongScatterMap
  ): List<Int> {
    listener.onProgressUpdate(COMPUTING_NATIVE_RETAINED_SIZE)

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

    graph.instanceSequence()
        .filter { it.className == "sun.misc.Cleaner" }
        .forEach { cleaner ->
          val thunkField = cleaner["sun.misc.Cleaner", "thunk"]
          val thunkId = thunkField?.value?.asNonNullObjectIdReference
          val referentId =
            cleaner["java.lang.ref.Reference", "referent"]?.value?.asNonNullObjectIdReference
          if (thunkId != null && referentId != null) {
            val thunkRecord = thunkField.value.asObject
            if (thunkRecord is GraphInstanceRecord && thunkRecord instanceOf "libcore.util.NativeAllocationRegistry\$CleanerThunk") {
              val allocationRegistryIdField =
                thunkRecord["libcore.util.NativeAllocationRegistry\$CleanerThunk", "this\$0"]
              if (allocationRegistryIdField != null && allocationRegistryIdField.value.isNonNullReference) {
                val allocationRegistryRecord = allocationRegistryIdField.value.asObject
                if (allocationRegistryRecord is GraphInstanceRecord && allocationRegistryRecord instanceOf "libcore.util.NativeAllocationRegistry") {
                  var nativeSize = nativeSizes.getValue(referentId)
                  nativeSize += allocationRegistryRecord["libcore.util.NativeAllocationRegistry", "size"]?.value?.asLong?.toInt()
                      ?: 0
                  nativeSizes[referentId] = nativeSize
                }
              }
            }
          }
        }

    listener.onProgressUpdate(COMPUTING_RETAINED_SIZE)

    val sizeByDominator = LinkedHashMap<Long, Int>().withDefault { 0 }

    // Include self size for leaking instances
    val leakingInstanceIds = mutableSetOf<Long>()
    results.forEach { result ->
      val leakingInstanceObjectId = result.leakingNode.instance
      leakingInstanceIds.add(leakingInstanceObjectId)
      val instanceRecord = graph.indexedObject(leakingInstanceObjectId).asInstance!!
      val classRecord = instanceRecord.instanceClass
      var retainedSize = sizeByDominator.getValue(leakingInstanceObjectId)

      retainedSize += classRecord.readRecord()
          .instanceSize
      sizeByDominator[leakingInstanceObjectId] = retainedSize
    }

    // Compute the size of each dominated instance and add to dominator
    dominatedInstances.forEach { instanceId, dominatorId ->
      // Avoid double reporting as those sizes will move up to the root dominator
      if (instanceId !in leakingInstanceIds) {
        val currentSize = sizeByDominator.getValue(dominatorId)
        val nativeSize = nativeSizes.getValue(instanceId)
        val shallowSize = graph.computeShallowSize(graph.indexedObject(instanceId))
        sizeByDominator[dominatorId] = currentSize + nativeSize + shallowSize
      }
    }

    // Move retained sizes from dominated leaking instance to dominators leaking instances.
    // Keep doing this until nothing moves.
    var sizedMoved: Boolean
    do {
      sizedMoved = false
      results.map { it.leakingNode.instance }
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
    return results.map { result ->
      sizeByDominator[result.leakingNode.instance]!!
    }
  }

  private fun buildLeakTraces(
    objectInspectors: List<ObjectInspector>,
    pathResults: List<Result>,
    graph: HprofGraph,
    analysisResults: MutableList<LeakingInstance>,
    retainedSizes: List<Int>?
  ) {
    listener.onProgressUpdate(BUILDING_LEAK_TRACES)

    val deduplicatedResults = deduplicateResults(pathResults)

    deduplicatedResults.forEachIndexed { index, pathResult ->
      val leakTrace =
        buildLeakTrace(graph, objectInspectors, pathResult.leakingNode)

      // We get the class name from the heap dump rather than the weak reference because primitive
      // arrays are more readable that way, e.g. "[C" at runtime vs "char[]" in the heap dump.
      val instanceClassName =
        recordClassName(graph.indexedObject(pathResult.leakingNode.instance))

      val leakDetected = LeakingInstance(
          instanceClassName = instanceClassName,
          exclusionStatus = pathResult.exclusionStatus, leakTrace = leakTrace,
          retainedHeapSize = retainedSizes?.get(index)
      )
      analysisResults += leakDetected
    }
  }

  private fun buildLeakTrace(
    graph: HprofGraph,
    objectInspectors: List<ObjectInspector>,
    leakingNode: LeakNode
  ): LeakTrace {
    val elements = ArrayList<LeakTraceElement>()
    // We iterate from the leak to the GC root
    val ignored = leakingNode.instance

    val leafNode = ChildNode(ignored, Int.MAX_VALUE, null, leakingNode, null)

    var node: LeakNode = leafNode
    val nodes = mutableListOf<LeakNode>()
    val leakReporters = mutableListOf<ObjectReporter>()
    while (node is ChildNode) {
      nodes.add(0, node.parent)
      leakReporters.add(
          0, ObjectReporter(graph.indexedObject(node.parent.instance))
      )
      node = node.parent
    }
    val rootNode = node as RootNode

    // Looping on inspectors first to get more cache hits.
    objectInspectors.forEach { inspector ->
      leakReporters.forEach { reporter ->
        inspector.inspect(graph, reporter)
      }
    }

    val leakStatuses = computeLeakStatuses(rootNode, leakReporters)

    node = leafNode
    while (node is ChildNode) {
      val index = (nodes.size - elements.size) - 1
      val leakReporter = leakReporters[index]
      val leakStatus = leakStatuses[index]
      elements.add(0, buildLeakElement(graph, node, leakReporter.labels, leakStatus))
      node = node.parent
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
      recordClassName(reporter.objectRecord).lastSegment('.')
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
    graph: HprofGraph,
    node: ChildNode,
    labels: List<String>,
    leakStatus: LeakNodeStatusAndReason
  ): LeakTraceElement {
    val objectId = node.parent.instance

    val graphRecord = graph.indexedObject(objectId)

    val className = recordClassName(graphRecord)

    val holderType = if (graphRecord is GraphClassRecord) {
      CLASS
    } else if (graphRecord is GraphObjectArrayRecord || graphRecord is GraphPrimitiveArrayRecord) {
      ARRAY
    } else {
      val instanceRecord = graphRecord.asInstance!!
      if (instanceRecord.instanceClass.classHierarchy.any { it.name == Thread::class.java.name }) {
        THREAD
      } else {
        OBJECT
      }
    }
    return LeakTraceElement(
        node.leakReference, holderType, className, node.exclusion, labels, leakStatus
    )
  }

  private fun recordClassName(
    graphRecord: GraphObjectRecord
  ): String {
    return when (graphRecord) {
      is GraphClassRecord -> graphRecord.name
      is GraphInstanceRecord -> graphRecord.className
      is GraphObjectArrayRecord -> graphRecord.arrayClassName
      is GraphPrimitiveArrayRecord -> graphRecord.arrayClassName
    }
  }

  private fun since(analysisStartNanoTime: Long): Long {
    return NANOSECONDS.toMillis(System.nanoTime() - analysisStartNanoTime)
  }

  companion object {
    private const val ANONYMOUS_CLASS_NAME_PATTERN = "^.+\\$\\d+$"
    internal val ANONYMOUS_CLASS_NAME_PATTERN_REGEX = ANONYMOUS_CLASS_NAME_PATTERN.toRegex()
  }
}
