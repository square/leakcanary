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
package leakcanary.experimental

import leakcanary.AnalyzerProgressListener
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
import leakcanary.HeapAnalysis
import leakcanary.HeapAnalysisException
import leakcanary.HeapAnalysisFailure
import leakcanary.HeapAnalysisSuccess
import leakcanary.HeapDump
import leakcanary.HeapDumpMemoryStore
import leakcanary.HeapValue
import leakcanary.HeapValue.BooleanValue
import leakcanary.HeapValue.ByteValue
import leakcanary.HeapValue.CharValue
import leakcanary.HeapValue.DoubleValue
import leakcanary.HeapValue.FloatValue
import leakcanary.HeapValue.IntValue
import leakcanary.HeapValue.LongValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HeapValue.ShortValue
import leakcanary.HprofParser
import leakcanary.HprofParser.RecordCallbacks
import leakcanary.KeyedWeakReference
import leakcanary.LeakReference
import leakcanary.LeakTrace
import leakcanary.LeakTraceElement
import leakcanary.LeakTraceElement.Holder
import leakcanary.LeakTraceElement.Holder.ARRAY
import leakcanary.LeakTraceElement.Holder.CLASS
import leakcanary.LeakTraceElement.Holder.OBJECT
import leakcanary.LeakTraceElement.Holder.THREAD
import leakcanary.LeakTraceElement.Type.ARRAY_ENTRY
import leakcanary.LeakTraceElement.Type.INSTANCE_FIELD
import leakcanary.LeakTraceElement.Type.STATIC_FIELD
import leakcanary.LeakingInstance
import leakcanary.NoPathToInstance
import leakcanary.Reachability
import leakcanary.Reachability.Status.REACHABLE
import leakcanary.Reachability.Status.UNKNOWN
import leakcanary.Reachability.Status.UNREACHABLE
import leakcanary.Record.HeapDumpRecord.GcRootRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.LoadClassRecord
import leakcanary.Record.StringRecord
import leakcanary.RetainedInstance
import leakcanary.WeakReferenceCleared
import leakcanary.WeakReferenceMissing
import leakcanary.experimental.internal.LeakNode
import leakcanary.experimental.internal.ShortestPathFinder
import leakcanary.experimental.internal.ShortestPathFinder.Result
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
    heapDump: HeapDump
  ): HeapAnalysis {
    val analysisStartNanoTime = System.nanoTime()

    if (!heapDump.heapDumpFile.exists()) {
      val exception = IllegalArgumentException("File does not exist: $heapDump.heapDumpFile")
      return HeapAnalysisFailure(
          heapDump, System.currentTimeMillis(), since(analysisStartNanoTime),
          HeapAnalysisException(exception)
      )
    }

    listener.onProgressUpdate(READING_HEAP_DUMP_FILE)


    try {
      HprofParser.open(heapDump.heapDumpFile)
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
                  heapDump, System.currentTimeMillis(), since(analysisStartNanoTime),
                  HeapAnalysisException(exception)
              )
            }

            val leakingWeakRefs =
              findLeakingReferences(
                  parser, retainedKeys, analysisResults, keyedWeakReferenceInstances,
                  heapDumpUptimeMillis
              )

            val pathResults = findShortestPaths(heapDump, parser, leakingWeakRefs, gcRootIds)

            buildLeakTraces(heapDump, pathResults, parser, leakingWeakRefs, analysisResults)

            addRemainingInstancesWithNoPath(parser, leakingWeakRefs, analysisResults)

            return HeapAnalysisSuccess(
                heapDump, System.currentTimeMillis(), since(analysisStartNanoTime),
                analysisResults.values.toList()
            )
          }
    } catch (exception: Throwable) {
      return HeapAnalysisFailure(
          heapDump, System.currentTimeMillis(), since(analysisStartNanoTime),
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
    heapDump: HeapDump,
    parser: HprofParser,
    leakingWeakRefs: List<KeyedWeakReferenceMirror>,
    gcRootIds: MutableList<Long>
  ): List<ShortestPathFinder.Result> {
    listener.onProgressUpdate(FINDING_SHORTEST_PATHS)

    val pathFinder = ShortestPathFinder(heapDump.excludedRefs)
    return pathFinder.findPaths(parser, leakingWeakRefs, gcRootIds)
  }

  private fun buildLeakTraces(
    heapDump: HeapDump,
    pathResults: List<Result>,
    parser: HprofParser,
    leakingWeakRefs: MutableList<KeyedWeakReferenceMirror>,
    analysisResults: MutableMap<String, RetainedInstance>
  ) {
    if (heapDump.computeRetainedHeapSize && pathResults.isNotEmpty()) {
      listener.onProgressUpdate(COMPUTING_DOMINATORS)
      // Computing dominators has the side effect of computing retained size.
      TODO("Dominators is not implemented, cannot compute retained heap size")
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

      val leakTrace = buildLeakTrace(parser, heapDump, pathResult.leakingNode)

      // TODO Compute retained heap size
      val retainedSize = null
      val key = parser.retrieveString(weakReference.key)
      val leakDetected = LeakingInstance(
          key,
          parser.retrieveString(weakReference.name),
          parser.retrieveString(weakReference.className),
          weakReference.watchDurationMillis, pathResult.excludingKnownLeaks, leakTrace, retainedSize
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
    heapDump: HeapDump,
    leakingNode: LeakNode
  ): LeakTrace {
    val elements = ArrayList<LeakTraceElement>()
    // We iterate from the leak to the GC root
    val ignored = leakingNode.instance
    var node: LeakNode? =
      LeakNode(null, ignored, leakingNode, null)
    while (node != null) {
      val element = buildLeakElement(parser, node)
      if (element != null) {
        elements.add(0, element)
      }
      node = node.parent
    }

    val expectedReachability = computeExpectedReachability(parser, heapDump, elements)

    return LeakTrace(elements, expectedReachability)
  }

  private fun computeExpectedReachability(
    parser: HprofParser,
    heapDump: HeapDump,
    elements: List<LeakTraceElement>
  ): List<Reachability> {
    var lastReachableElementIndex = 0
    val lastElementIndex = elements.size - 1
    var firstUnreachableElementIndex = lastElementIndex

    val expectedReachability = ArrayList<Reachability>()

    val reachabilityInspectors = mutableListOf<Reachability.Inspector>()
    for (reachabilityInspectorClass in heapDump.reachabilityInspectorClasses) {
      try {
        val defaultConstructor = reachabilityInspectorClass.getDeclaredConstructor()
        reachabilityInspectors.add(defaultConstructor.newInstance())
      } catch (e: Exception) {
        throw RuntimeException(e)
      }
    }

    for ((index, element) in elements.withIndex()) {
      val reachability = inspectElementReachability(parser, reachabilityInspectors, element)
      expectedReachability.add(reachability)
      if (reachability.status == REACHABLE) {
        lastReachableElementIndex = index
        // Reset firstUnreachableElementIndex so that we never have
        // firstUnreachableElementIndex < lastReachableElementIndex
        firstUnreachableElementIndex = lastElementIndex
      } else if (firstUnreachableElementIndex == lastElementIndex && reachability.status == UNREACHABLE) {
        firstUnreachableElementIndex = index
      }
    }

    if (expectedReachability[0].status == UNKNOWN) {
      expectedReachability[0] = Reachability.reachable("it's a GC root")
    }

    if (expectedReachability[lastElementIndex].status == UNKNOWN) {
      expectedReachability[lastElementIndex] =
        Reachability.unreachable("RefWatcher was watching this")
    }

    // First and last are always known.
    for (i in 1 until lastElementIndex) {
      val reachability = expectedReachability[i]
      if (reachability.status == UNKNOWN) {
        if (i < lastReachableElementIndex) {
          val nextReachableName = elements[i + 1].getSimpleClassName()
          expectedReachability[i] = Reachability.reachable("$nextReachableName↓ is not leaking")
        } else if (i > firstUnreachableElementIndex) {
          val previousUnreachableName = elements[i - 1].getSimpleClassName()
          expectedReachability[i] = Reachability.unreachable("$previousUnreachableName↑ is leaking")
        }
      }
    }
    return expectedReachability
  }

  private fun inspectElementReachability(
    parser: HprofParser,
    reachabilityInspectors: List<Reachability.Inspector>,
    element: LeakTraceElement
  ): Reachability {
    for (reachabilityInspector in reachabilityInspectors) {
      val reachability = reachabilityInspector.expectedReachability(element)
      if (reachability.status != UNKNOWN) {
        return reachability
      }
    }
    return Reachability.unknown()
  }

  private fun buildLeakElement(
    parser: HprofParser,
    node: LeakNode
  ): LeakTraceElement? {
    if (node.parent == null) {
      return null
    }
    val objectId = node.parent.instance

    val holderType: Holder
    var extra: String? = null

    val record = parser.retrieveRecordById(objectId)

    val leakReferences = describeFields(parser, record)

    val classHierarchy = when (record) {
      is ClassDumpRecord -> listOf(parser.className(record.id))
      is InstanceDumpRecord -> {
        val instance = parser.hydrateInstance(record)
        instance.classHierarchy.map { it.className }
      }
      is ObjectArrayDumpRecord -> listOf(parser.className(record.arrayClassId))
      else -> throw IllegalStateException("Unexpected record type for $record")
    }

    val className = classHierarchy[0]

    if (record is ClassDumpRecord) {
      holderType = CLASS
    } else if (record is ObjectArrayDumpRecord) {
      holderType = ARRAY
    } else {

      val instance = parser.hydrateInstance(record as InstanceDumpRecord)

      if (instance.classHierarchy.any { it.className == Thread::class.java.name }) {
        holderType = THREAD
        val nameField = instance.fieldValueOrNull<ObjectReference>("name")
        // Sometimes we can't find the String at the expected memory address in the heap dump.
        // See https://github.com/square/leakcanary/issues/417
        val threadName =
          if (nameField != null) parser.retrieveString(nameField) else "Thread name not available"
        extra = "(named '$threadName')"
      } else if (className.matches(ANONYMOUS_CLASS_NAME_PATTERN.toRegex())) {

        val parentClassName = instance.classHierarchy[1].className
        if (parentClassName == "java.lang.Object") {
          holderType = OBJECT
          try {
            // This is an anonymous class implementing an interface. The API does not give access
            // to the interfaces implemented by the class. We check if it's in the class path and
            // use that instead.
            val actualClass = Class.forName(instance.classHierarchy[0].className)
            val interfaces = actualClass.interfaces
            extra = if (interfaces.isNotEmpty()) {
              val implementedInterface = interfaces[0]
              "(anonymous implementation of " + implementedInterface.name + ")"
            } else {
              "(anonymous subclass of java.lang.Object)"
            }
          } catch (ignored: ClassNotFoundException) {
          }
        } else {
          holderType = OBJECT
          // Makes it easier to figure out which anonymous class we're looking at.
          extra = "(anonymous subclass of $parentClassName)"
        }
      } else {
        holderType = OBJECT
      }
    }
    return LeakTraceElement(
        node.leakReference, holderType, classHierarchy, extra,
        node.exclusion, leakReferences
    )
  }

  private fun describeFields(
    parser: HprofParser,
    record: ObjectRecord
  ): List<LeakReference> {
    val leakReferences = ArrayList<LeakReference>()
    when (record) {
      is ClassDumpRecord -> {
        // TODO We're loading all classes but reading only one. All this should be removed
        // it's only used by inspectors which should ask the parser for their needs.
        val classHierarchy = parser.hydrateClassHierarchy(record.id)
        val hydratedClass = classHierarchy[0]
        hydratedClass.staticFieldNames.forEachIndexed { index, fieldName ->

          val heapValue = hydratedClass.record.staticFields[index].value
          leakReferences.add(
              LeakReference(STATIC_FIELD, fieldName, heapValueAsString(heapValue))
          )
        }
      }
      is ObjectArrayDumpRecord -> record.elementIds.forEachIndexed { index, objectId ->
        val name = Integer.toString(index)
        leakReferences.add(LeakReference(ARRAY_ENTRY, name, "object $objectId"))
      }
      else -> {
        val instance = parser.hydrateInstance(record as InstanceDumpRecord)
        instance.classHierarchy[0].staticFieldNames.forEachIndexed { index, fieldName ->
          val heapValue = instance.classHierarchy[0].record.staticFields[index].value
          leakReferences.add(
              LeakReference(
                  STATIC_FIELD, fieldName,
                  heapValueAsString(heapValue)
              )
          )
        }
        instance.fieldValues.forEachIndexed { classIndex, fieldValues ->
          fieldValues.forEachIndexed { fieldIndex, heapValue ->
            leakReferences.add(
                LeakReference(
                    INSTANCE_FIELD, instance.classHierarchy[classIndex].fieldNames[fieldIndex],
                    heapValueAsString(heapValue)
                )
            )
          }
        }
      }
    }
    return leakReferences
  }

  private fun heapValueAsString(heapValue: HeapValue): String {
    return when (heapValue) {
      is ObjectReference -> if (heapValue.value == 0L) "null" else "object ${heapValue.value}"
      is BooleanValue -> heapValue.value.toString()
      is CharValue -> heapValue.value.toString()
      is FloatValue -> heapValue.value.toString()
      is DoubleValue -> heapValue.value.toString()
      is ByteValue -> heapValue.value.toString()
      is ShortValue -> heapValue.value.toString()
      is IntValue -> heapValue.value.toString()
      is LongValue -> heapValue.value.toString()
    }
  }

  private fun since(analysisStartNanoTime: Long): Long {
    return NANOSECONDS.toMillis(System.nanoTime() - analysisStartNanoTime)
  }

  companion object {

    private const val ANONYMOUS_CLASS_NAME_PATTERN = "^.+\\$\\d+$"
  }
}
