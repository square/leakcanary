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
import leakcanary.HprofPushRecordsParser.OnRecordListener
import leakcanary.LeakNode.ChildNode
import leakcanary.LeakNodeStatus.LEAKING
import leakcanary.LeakNodeStatus.NOT_LEAKING
import leakcanary.LeakNodeStatus.UNKNOWN
import leakcanary.LeakTraceElement.Holder.ARRAY
import leakcanary.LeakTraceElement.Holder.CLASS
import leakcanary.LeakTraceElement.Holder.OBJECT
import leakcanary.LeakTraceElement.Holder.THREAD
import leakcanary.PrimitiveType.BOOLEAN
import leakcanary.PrimitiveType.BYTE
import leakcanary.PrimitiveType.CHAR
import leakcanary.PrimitiveType.DOUBLE
import leakcanary.PrimitiveType.FLOAT
import leakcanary.PrimitiveType.INT
import leakcanary.PrimitiveType.LONG
import leakcanary.PrimitiveType.SHORT
import leakcanary.Record.HeapDumpRecord.GcRootRecord
import leakcanary.internal.KeyedWeakReferenceMirror
import leakcanary.internal.ShortestPathFinder
import leakcanary.internal.ShortestPathFinder.Result
import leakcanary.internal.ShortestPathFinder.Results
import leakcanary.internal.hppc.LongLongScatterMap
import leakcanary.internal.lastSegment
import java.io.Closeable
import java.io.File
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit.NANOSECONDS
import kotlin.reflect.KClass

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
    leakTraceInspectors: List<LeakTraceInspector> = emptyList()
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
      val (graph, hprofCloseable, gcRootIds, keyedWeakReferenceInstances, cleaners) = scan(
          heapDumpFile, computeRetainedHeapSize
      )
      hprofCloseable.use {
        val analysisResults = mutableMapOf<String, RetainedInstance>()
        listener.onProgressUpdate(FINDING_WATCHED_REFERENCES)

        val retainedWeakRefs = findLeakingReferences(graph, keyedWeakReferenceInstances)

        if (retainedWeakRefs.isEmpty()) {
          val exception = IllegalStateException("No retained instances found in heap dump")
          return HeapAnalysisFailure(
              heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
              HeapAnalysisException(exception)
          )
        }

        val (pathResults, dominatedInstances) =
          findShortestPaths(
              graph, exclusions, retainedWeakRefs, gcRootIds,
              computeRetainedHeapSize
          )

        val retainedSizes = if (computeRetainedHeapSize) {
          computeRetainedSizes(graph, pathResults, dominatedInstances, cleaners)
        } else {
          null
        }

        buildLeakTraces(
            leakTraceInspectors, pathResults, graph,
            retainedWeakRefs, analysisResults, retainedSizes
        )

        addRemainingInstancesWithNoPath(retainedWeakRefs, analysisResults)

        return HeapAnalysisSuccess(
            heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
            analysisResults.values.toList()
        )
      }
    } catch (exception: Throwable) {
      return HeapAnalysisFailure(
          heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
          HeapAnalysisException(exception)
      )
    }
  }

  private data class ScanResult(
    val graph: HprofGraph,
    val hprofCloseable: Closeable,
    val gcRootIds: MutableList<GcRoot>,
    val keyedWeakReferenceInstances: List<GraphInstanceRecord>,
    val cleaners: MutableList<Long>
  )

  private fun scan(
    hprofFile: File,
    computeRetainedSize: Boolean
  ): ScanResult {
    val gcRoot = mutableListOf<GcRoot>()
    val cleaners = mutableListOf<Long>()

    val recordListener = object : OnRecordListener {
      override fun recordTypes(): Set<KClass<out Record>> = setOf(GcRootRecord::class)

      override fun onTypeSizesAvailable(typeSizes: Map<Int, Int>) {
      }

      override fun onRecord(
        position: Long,
        record: Record
      ) {
        when (record) {
          is GcRootRecord -> {
            // TODO Ignoring VmInternal because we've got 150K of it, but is this the right thing
            // to do? What's VmInternal exactly? History does not go further than
            // https://android.googlesource.com/platform/dalvik2/+/refs/heads/master/hit/src/com/android/hit/HprofParser.java#77
            // We should log to figure out what objects VmInternal points to.
            when (record.gcRoot) {
              // ThreadObject points to threads, which we need to find the thread that a JavaLocalExclusion
              // belongs to
              is ThreadObject,
              is JniGlobal,
              is JniLocal,
              is JavaFrame,
              is NativeStack,
              is StickyClass,
              is ThreadBlock,
              is MonitorUsed,
                // TODO What is this and why do we care about it as a root?
              is ReferenceCleanup,
              is JniMonitor
              -> {
                gcRoot.add(record.gcRoot)
              }
            }
          }
          else -> {
            throw IllegalArgumentException("Unexpected record $record")
          }
        }
      }
    }
    val (graph, hprofCloseable) = HprofGraph.readHprof(hprofFile, recordListener)

    val keyedWeakReferenceInstances = mutableListOf<GraphInstanceRecord>()
    graph.instanceSequence()
        .forEach { instance ->
          val className = instance.className
          if (className == "leakcanary.KeyedWeakReference" || className == "com.squareup.leakcanary.KeyedWeakReference") {
            keyedWeakReferenceInstances.add(instance)
          } else if (computeRetainedSize && className == "sun.misc.Cleaner") {
            cleaners.add(instance.objectId)
          }
        }
    return ScanResult(graph, hprofCloseable, gcRoot, keyedWeakReferenceInstances, cleaners)
  }

  private fun findLeakingReferences(
    graph: HprofGraph,
    keyedWeakReferenceInstances: List<GraphInstanceRecord>
  ): MutableList<KeyedWeakReferenceMirror> {

    val keyedWeakReferenceClass = graph.indexedClass(KeyedWeakReference::class.java.name)

    val heapDumpUptimeMillis = if (keyedWeakReferenceClass == null) {
      null
    } else {
      keyedWeakReferenceClass["heapDumpUptimeMillis"]?.value?.asLong
    }

    if (heapDumpUptimeMillis == null) {
      CanaryLog.d(
          "${KeyedWeakReference::class.java.name}.heapDumpUptimeMillis field not found, " +
              "this must be a heap dump from an older version of LeakCanary."
      )
    }

    val retainedInstances = mutableListOf<KeyedWeakReferenceMirror>()
    keyedWeakReferenceInstances.forEach { record ->
      val weakRef =
        KeyedWeakReferenceMirror.fromInstance(record, heapDumpUptimeMillis)
      if (weakRef.isRetained && weakRef.hasReferent) {
        retainedInstances.add(weakRef)
      }
    }
    return retainedInstances
  }

  private fun findShortestPaths(
    graph: HprofGraph,
    exclusions: List<Exclusion>,
    leakingWeakRefs: List<KeyedWeakReferenceMirror>,
    gcRootIds: MutableList<GcRoot>,
    computeDominators: Boolean
  ): Results {
    val pathFinder = ShortestPathFinder()
    return pathFinder.findPaths(
        graph, exclusions, leakingWeakRefs, gcRootIds, computeDominators, listener
    )
  }

  private fun computeRetainedSizes(
    graph: HprofGraph,
    results: List<Result>,
    dominatedInstances: LongLongScatterMap,
    cleaners: MutableList<Long>
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

    cleaners.forEach { objectId ->
      val cleaner = graph.indexedObject(objectId).asInstance!!
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
      val leakingInstanceId = result.weakReference.referent.value
      leakingInstanceIds.add(leakingInstanceId)
      val instanceRecord = graph.indexedObject(leakingInstanceId).asInstance!!
      val classRecord = instanceRecord.instanceClass
      var retainedSize = sizeByDominator.getValue(leakingInstanceId)

      retainedSize += classRecord.readRecord()
          .instanceSize
      sizeByDominator[leakingInstanceId] = retainedSize
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
      results.map { it.weakReference.referent.value }
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
      sizeByDominator[result.weakReference.referent.value]!!
    }
  }

  private fun buildLeakTraces(
    leakTraceInspectors: List<LeakTraceInspector>,
    pathResults: List<Result>,
    graph: HprofGraph,
    leakingWeakRefs: MutableList<KeyedWeakReferenceMirror>,
    analysisResults: MutableMap<String, RetainedInstance>,
    retainedSizes: List<Int>?
  ) {
    listener.onProgressUpdate(BUILDING_LEAK_TRACES)

    pathResults.forEachIndexed { index, pathResult ->
      val weakReference = pathResult.weakReference
      val removed = leakingWeakRefs.remove(weakReference)
      if (!removed) {
        throw IllegalStateException(
            "ShortestPathFinder found an instance we didn't ask it to find: $pathResult"
        )
      }

      val leakTrace =
        buildLeakTrace(graph, leakTraceInspectors, pathResult.leakingNode)

      // We get the class name from the heap dump rather than the weak reference because primitive
      // arrays are more readable that way, e.g. "[C" at runtime vs "char[]" in the heap dump.
      val instanceClassName =
        recordClassName(graph.indexedObject(pathResult.leakingNode.instance))

      val leakDetected = LeakingInstance(
          referenceKey = weakReference.key,
          referenceName = weakReference.name,
          instanceClassName = instanceClassName,
          watchDurationMillis = weakReference.watchDurationMillis,
          retainedDurationMillis = weakReference.retainedDurationMillis ?: 0,
          exclusionStatus = pathResult.exclusionStatus, leakTrace = leakTrace,
          retainedHeapSize = retainedSizes?.get(index)
      )
      analysisResults[weakReference.key] = leakDetected
    }
  }

  private fun addRemainingInstancesWithNoPath(
    leakingWeakRefs: List<KeyedWeakReferenceMirror>,
    analysisResults: MutableMap<String, RetainedInstance>
  ) {
    leakingWeakRefs.forEach { refWithNoPath ->
      val key = refWithNoPath.key
      val name = refWithNoPath.name
      val className = refWithNoPath.className
      val noLeak = NoPathToInstance(
          key, name, className, refWithNoPath.watchDurationMillis,
          refWithNoPath.retainedDurationMillis ?: 0
      )
      analysisResults[key] = noLeak
    }
  }

  private fun buildLeakTrace(
    graph: HprofGraph,
    leakTraceInspectors: List<LeakTraceInspector>,
    leakingNode: LeakNode
  ): LeakTrace {
    val elements = ArrayList<LeakTraceElement>()
    // We iterate from the leak to the GC root
    val ignored = leakingNode.instance

    val leafNode = ChildNode(ignored, Int.MAX_VALUE, null, leakingNode, null)

    var node: LeakNode = leafNode
    val nodes = mutableListOf<LeakNode>()
    val leakReporters = mutableListOf<LeakTraceElementReporter>()
    while (node is ChildNode) {
      nodes.add(0, node.parent)
      leakReporters.add(
          0, LeakTraceElementReporter(graph.indexedObject(node.parent.instance))
      )
      node = node.parent
    }

    leakTraceInspectors.forEach { it.inspect(graph, leakReporters) }

    val leakStatuses = computeLeakStatuses(leakReporters)

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
    leakReporters: List<LeakTraceElementReporter>
  ): List<LeakNodeStatusAndReason> {
    var lastNotLeakingElementIndex = 0
    val lastElementIndex = leakReporters.size - 1
    var firstLeakingElementIndex = lastElementIndex

    val leakStatuses = ArrayList<LeakNodeStatusAndReason>()

    for ((index, reporter) in leakReporters.withIndex()) {
      val leakStatus = inspectElementLeakStatus(reporter)
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

    leakStatuses[0] = when (leakStatuses[0].status) {
      UNKNOWN -> LeakNodeStatus.notLeaking("it's a GC root")
      NOT_LEAKING -> LeakNodeStatus.notLeaking(
          "it's a GC root and ${leakStatuses[0].reason}"
      )
      LEAKING -> LeakNodeStatus.notLeaking(
          "it's a GC root. Conflicts with ${leakStatuses[0].reason}"
      )
    }

    leakStatuses[lastElementIndex] = when (leakStatuses[lastElementIndex].status) {
      UNKNOWN -> LeakNodeStatus.leaking("RefWatcher was watching this")
      LEAKING -> LeakNodeStatus.leaking(
          "RefWatcher was watching this and ${leakStatuses[lastElementIndex].reason}"
      )
      NOT_LEAKING -> LeakNodeStatus.leaking(
          "RefWatcher was watching this. Conflicts with ${leakStatuses[lastElementIndex].reason}"
      )
    }

    val simpleClassNames = leakReporters.map { reporter ->
      recordClassName(reporter.objectRecord).lastSegment('.')
    }

    // First and last are always known.
    for (i in 1 until lastElementIndex) {
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

  private fun inspectElementLeakStatus(
    reporter: LeakTraceElementReporter
  ): LeakNodeStatusAndReason {
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
      is GraphPrimitiveArrayRecord -> when (graphRecord.primitiveType) {
        BOOLEAN -> "boolean[]"
        CHAR -> "char[]"
        FLOAT -> "float[]"
        DOUBLE -> "double[]"
        BYTE -> "byte[]"
        SHORT -> "short[]"
        INT -> "int[]"
        LONG -> "long[]"
      }
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
