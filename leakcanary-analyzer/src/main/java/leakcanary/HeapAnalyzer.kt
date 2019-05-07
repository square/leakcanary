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
import leakcanary.AnalyzerProgressListener.Step.COMPUTING_DOMINATORS
import leakcanary.AnalyzerProgressListener.Step.FINDING_LEAKING_REFS
import leakcanary.AnalyzerProgressListener.Step.FINDING_SHORTEST_PATHS
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
import leakcanary.HeapValue.LongValue
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
import leakcanary.Record.LoadClassRecord
import leakcanary.Record.StringRecord
import leakcanary.internal.KeyedWeakReferenceMirror
import leakcanary.internal.ShortestPathFinder
import leakcanary.internal.ShortestPathFinder.Result
import leakcanary.internal.lastSegment
import java.io.File
import java.util.ArrayList
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
            val (gcRootIds, heapDumpMemoryStoreClassId, keyedWeakReferenceInstances) = scan(parser)
            val analysisResults = mutableMapOf<String, RetainedInstance>()
            listener.onProgressUpdate(FINDING_WATCHED_REFERENCES)
            val (retainedKeys, heapDumpUptimeMillis) = readHeapDumpMemoryStore(
                parser, heapDumpMemoryStoreClassId
            )

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

            val pathResults =
              findShortestPaths(parser, exclusionsFactory, leakingWeakRefs, gcRootIds)

            buildLeakTraces(
                computeRetainedHeapSize, reachabilityInspectors, labelers, pathResults, parser,
                leakingWeakRefs, analysisResults
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

  private fun scan(parser: HprofParser): Triple<MutableList<Long>, Long, List<InstanceDumpRecord>> {
    var keyedWeakReferenceStringId = -1L
    var heapDumpMemoryStoreStringId = -1L
    var keyedWeakReferenceClassId = -1L
    var heapDumpMemoryStoreClassId = -1L
    val keyedWeakReferenceInstances = mutableListOf<InstanceDumpRecord>()
    val gcRootIds = mutableListOf<Long>()
    val callbacks = RecordCallbacks()
        .on(StringRecord::class.java) {
          if (it.string == KeyedWeakReference::class.java.name) {
            keyedWeakReferenceStringId = it.id
          } else if (it.string == HeapDumpMemoryStore::class.java.name) {
            heapDumpMemoryStoreStringId = it.id
          }
        }
        .on(LoadClassRecord::class.java) {
          if (it.classNameStringId == keyedWeakReferenceStringId) {
            keyedWeakReferenceClassId = it.id
          } else if (it.classNameStringId == heapDumpMemoryStoreStringId) {
            heapDumpMemoryStoreClassId = it.id
          }
        }
        .on(InstanceDumpRecord::class.java) {
          if (it.classId == keyedWeakReferenceClassId) {
            keyedWeakReferenceInstances.add(it)
          }
        }
        .on(GcRootRecord::class.java) {
          // TODO Why is ThreadObject ignored?
          // TODO Ignoring VmInternal because we've got 150K of it, but is this the right thing
          // to do? What's VmInternal exactly? History does not go further than
          // https://android.googlesource.com/platform/dalvik2/+/refs/heads/master/hit/src/com/android/hit/HprofParser.java#77
          // We should log to figure out what objects VmInternal points to.
          when (it.gcRoot) {
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
              gcRootIds.add(it.gcRoot.id)
            }
          }
        }
    parser.scan(callbacks)
    return Triple(gcRootIds, heapDumpMemoryStoreClassId, keyedWeakReferenceInstances)
  }

  private fun readHeapDumpMemoryStore(
    parser: HprofParser,
    heapDumpMemoryStoreClassId: Long
  ): Pair<MutableSet<Long>, Long> {
    val storeClass = parser.hydrateClassHierarchy(heapDumpMemoryStoreClassId)[0]
    val retainedKeysForHeapDump = (parser.retrieveRecord(
        storeClass.staticFieldValue("retainedKeysForHeapDump")
    ) as ObjectArrayDumpRecord).elementIds.toMutableSet()
    val heapDumpUptimeMillis =
      storeClass.staticFieldValue<LongValue>("heapDumpUptimeMillis")
          .value
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
    gcRootIds: MutableList<Long>
  ): List<Result> {
    listener.onProgressUpdate(FINDING_SHORTEST_PATHS)

    val pathFinder = ShortestPathFinder()
    return pathFinder.findPaths(parser, exclusionsFactory, leakingWeakRefs, gcRootIds)
  }

  private fun buildLeakTraces(
    computeRetainedHeapSize: Boolean,
    leakInspectors: List<LeakInspector>,
    labelers: List<Labeler>,
    pathResults: List<Result>,
    parser: HprofParser,
    leakingWeakRefs: MutableList<KeyedWeakReferenceMirror>,
    analysisResults: MutableMap<String, RetainedInstance>
  ) {
    if (computeRetainedHeapSize && pathResults.isNotEmpty()) {
      listener.onProgressUpdate(COMPUTING_DOMINATORS)
      // Computing dominators has the side effect of computing retained size.
      CanaryLog.d("Cannot compute retained heap size because dominators is not implemented yet")
    }

    listener.onProgressUpdate(BUILDING_LEAK_TRACES)

    pathResults.forEach { pathResult ->
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

      // TODO Compute retained heap size
      val retainedSize = null
      val key = parser.retrieveString(weakReference.key)
      val leakDetected = LeakingInstance(
          referenceKey = key,
          referenceName = parser.retrieveString(weakReference.name),
          instanceClassName = instanceClassName,
          watchDurationMillis = weakReference.watchDurationMillis,
          exclusionStatus = pathResult.exclusionStatus, leakTrace = leakTrace,
          retainedHeapSize = retainedSize
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
