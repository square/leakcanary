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
import leakcanary.AnalyzerProgressListener.Step.FINDING_LEAKING_REFS
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
import leakcanary.HprofParser.RecordCallbacks
import leakcanary.LeakNode.ChildNode
import leakcanary.LeakNodeStatus.LEAKING
import leakcanary.LeakNodeStatus.NOT_LEAKING
import leakcanary.LeakNodeStatus.UNKNOWN
import leakcanary.LeakTraceElement.Holder.ARRAY
import leakcanary.LeakTraceElement.Holder.CLASS
import leakcanary.LeakTraceElement.Holder.OBJECT
import leakcanary.LeakTraceElement.Holder.THREAD
import leakcanary.Record.HeapDumpRecord.GcRootRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import leakcanary.internal.KeyedWeakReferenceMirror
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
    exclusionsFactory: ExclusionsFactory = { emptyList() },
    computeRetainedHeapSize: Boolean = false,
    reachabilityInspectors: List<LeakInspector> = emptyList(),
    labelers: List<Labeler> = emptyList()
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
      HprofParser.open(heapDumpFile)
          .use { parser ->
            listener.onProgressUpdate(SCANNING_HEAP_DUMP)
            val (gcRootIds, keyedWeakReferenceInstances, cleaners) = scan(
                parser, computeRetainedHeapSize
            )
            val analysisResults = mutableMapOf<String, RetainedInstance>()
            listener.onProgressUpdate(FINDING_WATCHED_REFERENCES)
            val (retainedKeys, heapDumpUptimeMillis) = readHeapDumpMemoryStore(parser)

            if (retainedKeys.isEmpty()) {
              val exception = IllegalStateException("No retained keys found in heap dump")
              return HeapAnalysisFailure(
                  heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
                  HeapAnalysisException(exception)
              )
            }

            val leakingWeakRefs =
              findLeakingReferences(
                  parser, retainedKeys, analysisResults, keyedWeakReferenceInstances,
                  heapDumpUptimeMillis
              )

            val (pathResults, dominatedInstances) =
              findShortestPaths(
                  parser, exclusionsFactory, leakingWeakRefs, gcRootIds,
                  computeRetainedHeapSize
              )

            val retainedSizes = if (computeRetainedHeapSize) {
              computeRetainedSizes(parser, pathResults, dominatedInstances, cleaners)
            } else {
              null
            }

            buildLeakTraces(
                reachabilityInspectors, labelers, pathResults, parser,
                leakingWeakRefs, analysisResults, retainedSizes
            )

            addRemainingInstancesWithNoPath(parser, leakingWeakRefs, analysisResults)

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
    val gcRootIds: MutableList<GcRoot>,
    val keyedWeakReferenceInstances: List<InstanceDumpRecord>,
    val cleaners: MutableList<Long>
  )

  private fun scan(
    parser: HprofParser,
    computeRetainedSize: Boolean
  ): ScanResult {
    val keyedWeakReferenceInstances = mutableListOf<InstanceDumpRecord>()
    val gcRoot = mutableListOf<GcRoot>()
    val cleaners = mutableListOf<Long>()
    val callbacks = RecordCallbacks()
        .on(InstanceDumpRecord::class.java) { record ->
          when (parser.className(record.classId)) {
            KeyedWeakReference::class.java.name -> keyedWeakReferenceInstances.add(record)
            "sun.misc.Cleaner" -> if (computeRetainedSize) cleaners.add(record.id)
          }
        }
        .on(GcRootRecord::class.java) {
          // TODO Ignoring VmInternal because we've got 150K of it, but is this the right thing
          // to do? What's VmInternal exactly? History does not go further than
          // https://android.googlesource.com/platform/dalvik2/+/refs/heads/master/hit/src/com/android/hit/HprofParser.java#77
          // We should log to figure out what objects VmInternal points to.
          when (it.gcRoot) {
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
              gcRoot.add(it.gcRoot)
            }
          }
        }
    parser.scan(callbacks)
    return ScanResult(gcRoot, keyedWeakReferenceInstances, cleaners)
  }

  private fun readHeapDumpMemoryStore(
    parser: HprofParser
  ): Pair<MutableSet<Long>, Long> = with(parser) {
    val heapDumpMemoryStoreClassId = parser.classId(HeapDumpMemoryStore::class.java.name)!!
    val storeClass = parser.hydrateClassHierarchy(heapDumpMemoryStoreClassId)[0]
    val retainedKeysRecord =
      storeClass["retainedKeysForHeapDump"].reference!!.objectRecord as ObjectArrayDumpRecord

    val retainedKeysForHeapDump = retainedKeysRecord.elementIds.toMutableSet()
    val heapDumpUptimeMillis = storeClass["heapDumpUptimeMillis"].long!!
    return retainedKeysForHeapDump to heapDumpUptimeMillis
  }

  private fun findLeakingReferences(
    parser: HprofParser,
    retainedKeys: MutableSet<Long>,
    analysisResults: MutableMap<String, RetainedInstance>,
    keyedWeakReferenceInstances: List<InstanceDumpRecord>,
    heapDumpUptimeMillis: Long
  ): MutableList<KeyedWeakReferenceMirror> {
    listener.onProgressUpdate(FINDING_LEAKING_REFS)

    val leakingWeakRefs = mutableListOf<KeyedWeakReferenceMirror>()

    keyedWeakReferenceInstances.forEach { record ->
      val weakRef =
        KeyedWeakReferenceMirror.fromInstance(parser.hydrateInstance(record), heapDumpUptimeMillis)
      val wasRetained = retainedKeys.remove(weakRef.key.value)
      if (wasRetained) {
        if (weakRef.hasReferent) {
          leakingWeakRefs.add(weakRef)
        } else {
          val key = parser.retrieveString(weakRef.key)
          val name = parser.retrieveString(weakRef.name)
          val className = parser.retrieveString(weakRef.className)
          val noLeak = WeakReferenceCleared(key, name, className, weakRef.watchDurationMillis)
          analysisResults[key] = noLeak
        }
      }
    }
    retainedKeys.forEach { referenceKeyId ->
      // This could happen if RefWatcher removed weakly reachable references after providing
      // the set of retained keys
      val referenceKey = parser.retrieveStringById(referenceKeyId)
      val noLeak = WeakReferenceMissing(referenceKey)
      analysisResults[referenceKey] = noLeak
    }
    return leakingWeakRefs
  }

  private fun findShortestPaths(
    parser: HprofParser,
    exclusionsFactory: ExclusionsFactory,
    leakingWeakRefs: List<KeyedWeakReferenceMirror>,
    gcRootIds: MutableList<GcRoot>,
    computeDominators: Boolean
  ): Results {
    val pathFinder = ShortestPathFinder()
    return pathFinder.findPaths(
        parser, exclusionsFactory, leakingWeakRefs, gcRootIds, computeDominators, listener
    )
  }

  private fun computeRetainedSizes(
    parser: HprofParser,
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

    with(parser) {
      cleaners.forEach {
        val cleaner = it.hydratedInstance
        val thunkId = cleaner["thunk"].reference
        val referentId = cleaner["referent"].reference
        if (thunkId != null && referentId != null) {
          val thunkRecord = thunkId.objectRecord
          if (thunkRecord instanceOf "libcore.util.NativeAllocationRegistry\$CleanerThunk") {
            val thunkRunnable = thunkRecord.hydratedInstance
            val allocationRegistryId = thunkRunnable["this\$0"].reference
            if (allocationRegistryId != null) {
              val allocationRegistryRecord = allocationRegistryId.objectRecord
              if (allocationRegistryRecord instanceOf "libcore.util.NativeAllocationRegistry") {
                val allocationRegistry = allocationRegistryRecord.hydratedInstance
                var nativeSize = nativeSizes.getValue(referentId)
                nativeSize += allocationRegistry["size"].long?.toInt() ?: 0
                nativeSizes[referentId] = nativeSize
              }
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
      val instanceRecord =
        parser.retrieveRecordById(leakingInstanceId) as InstanceDumpRecord
      val classRecord =
        parser.retrieveRecordById(instanceRecord.classId) as ClassDumpRecord
      var retainedSize = sizeByDominator.getValue(leakingInstanceId)

      retainedSize += classRecord.instanceSize
      sizeByDominator[leakingInstanceId] = retainedSize
    }

    // Compute the size of each dominated instance and add to dominator
    dominatedInstances.forEach { instanceId, dominatorId ->
      // Avoid double reporting as those sizes will move up to the root dominator
      if (instanceId !in leakingInstanceIds) {
        val currentSize = sizeByDominator.getValue(dominatorId)
        val record = parser.retrieveRecordById(instanceId)
        val nativeSize = nativeSizes.getValue(instanceId)
        val shallowSize = when (record) {
          is InstanceDumpRecord -> {
            val classRecord = parser.retrieveRecordById(record.classId) as ClassDumpRecord
            // Note: instanceSize is the sum of shallow size through the class hierarchy
            classRecord.instanceSize
          }
          is ObjectArrayDumpRecord -> record.elementIds.size * parser.idSize
          is BooleanArrayDump -> record.array.size * HprofReader.BOOLEAN_SIZE
          is CharArrayDump -> record.array.size * HprofReader.CHAR_SIZE
          is FloatArrayDump -> record.array.size * HprofReader.FLOAT_SIZE
          is DoubleArrayDump -> record.array.size * HprofReader.DOUBLE_SIZE
          is ByteArrayDump -> record.array.size * HprofReader.BYTE_SIZE
          is ShortArrayDump -> record.array.size * HprofReader.SHORT_SIZE
          is IntArrayDump -> record.array.size * HprofReader.INT_SIZE
          is LongArrayDump -> record.array.size * HprofReader.LONG_SIZE
          else -> {
            throw IllegalStateException("Unexpected record $record")
          }
        }
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
    leakInspectors: List<LeakInspector>,
    labelers: List<Labeler>,
    pathResults: List<Result>,
    parser: HprofParser,
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
        buildLeakTrace(parser, leakInspectors, pathResult.leakingNode, labelers)

      // We get the class name from the heap dump rather than the weak reference because primitive
      // arrays are more readable that way, e.g. "[C" at runtime vs "char[]" in the heap dump.
      val instanceClassName =
        recordClassName(parser.retrieveRecordById(pathResult.leakingNode.instance), parser)

      val key = parser.retrieveString(weakReference.key)
      val leakDetected = LeakingInstance(
          referenceKey = key,
          referenceName = parser.retrieveString(weakReference.name),
          instanceClassName = instanceClassName,
          watchDurationMillis = weakReference.watchDurationMillis,
          exclusionStatus = pathResult.exclusionStatus, leakTrace = leakTrace,
          retainedHeapSize = retainedSizes?.get(index)
      )
      analysisResults[key] = leakDetected
    }
  }

  private fun addRemainingInstancesWithNoPath(
    hprofParser: HprofParser,
    leakingWeakRefs: List<KeyedWeakReferenceMirror>,
    analysisResults: MutableMap<String, RetainedInstance>
  ) {
    leakingWeakRefs.forEach { refWithNoPath ->
      val key = hprofParser.retrieveString(refWithNoPath.key)
      val name = hprofParser.retrieveString(refWithNoPath.name)
      val className = hprofParser.retrieveString(refWithNoPath.className)
      val noLeak = NoPathToInstance(key, name, className, refWithNoPath.watchDurationMillis)
      analysisResults[key] = noLeak
    }
  }

  private fun buildLeakTrace(
    parser: HprofParser,
    leakInspectors: List<LeakInspector>,
    leakingNode: LeakNode,
    labelers: List<Labeler>
  ): LeakTrace {
    val elements = ArrayList<LeakTraceElement>()
    // We iterate from the leak to the GC root
    val ignored = leakingNode.instance

    val leafNode = ChildNode(ignored, Int.MAX_VALUE, null, leakingNode, null)

    var node: LeakNode = leafNode
    val nodes = mutableListOf<LeakNode>()
    while (node is ChildNode) {
      nodes.add(0, node.parent)
      node = node.parent
    }
    val leakStatuses = computeLeakStatuses(parser, leakInspectors, nodes)

    node = leafNode
    while (node is ChildNode) {
      val labels = mutableListOf<String>()
      for (labeler in labelers) {
        labels.addAll(labeler(parser, node.parent))
      }
      val index = (nodes.size - elements.size) - 1
      val leakStatus = leakStatuses[index]
      elements.add(0, buildLeakElement(parser, node, labels, leakStatus))
      node = node.parent
    }
    return LeakTrace(elements)
  }

  private fun computeLeakStatuses(
    parser: HprofParser,
    leakInspectors: List<LeakInspector>,
    nodes: List<LeakNode>
  ): List<LeakNodeStatusAndReason> {
    var lastNotLeakingElementIndex = 0
    val lastElementIndex = nodes.size - 1
    var firstLeakingElementIndex = lastElementIndex

    val leakStatuses = ArrayList<LeakNodeStatusAndReason>()

    for ((index, node) in nodes.withIndex()) {
      val leakStatus = inspectElementLeakStatus(leakInspectors, parser, node)
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

    val simpleClassNames = nodes.map { node ->
      recordClassName(parser.retrieveRecordById(node.instance), parser).lastSegment('.')
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
    leakInspectors: List<LeakInspector>,
    parser: HprofParser,
    node: LeakNode
  ): LeakNodeStatusAndReason {
    var current = LeakNodeStatus.unknown()
    for (leakInspector in leakInspectors) {
      val statusAndReason = leakInspector(parser, node)
      if (statusAndReason.status != UNKNOWN) {
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
    }
    return current
  }

  private fun buildLeakElement(
    parser: HprofParser,
    node: ChildNode,
    labels: List<String>,
    leakStatus: LeakNodeStatusAndReason
  ): LeakTraceElement {
    val objectId = node.parent.instance

    val record = parser.retrieveRecordById(objectId)

    val className = recordClassName(record, parser)

    val holderType = if (record is ClassDumpRecord) {
      CLASS
    } else if (record is ObjectArrayDumpRecord || record is PrimitiveArrayDumpRecord) {
      ARRAY
    } else {
      record as InstanceDumpRecord
      val classHierarchy = parser.hydrateClassHierarchy(record.classId)
      if (classHierarchy.any { it.className == Thread::class.java.name }) {
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
    record: ObjectRecord,
    parser: HprofParser
  ): String {
    return when (record) {
      is ClassDumpRecord -> parser.className(record.id)
      is InstanceDumpRecord -> parser.className(record.classId)
      is ObjectArrayDumpRecord -> parser.className(record.arrayClassId)
      is BooleanArrayDump -> "boolean[]"
      is CharArrayDump -> "char[]"
      is FloatArrayDump -> "float[]"
      is DoubleArrayDump -> "double[]"
      is ByteArrayDump -> "byte[]"
      is ShortArrayDump -> "short[]"
      is IntArrayDump -> "int[]"
      is LongArrayDump -> "long[]"
      else -> throw IllegalStateException("Unexpected record type for $record")
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
