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
package leakcanary.internal.perflib

import com.android.tools.perflib.captures.MemoryMappedFileBuffer
import com.squareup.haha.perflib.ArrayInstance
import com.squareup.haha.perflib.ClassObj
import com.squareup.haha.perflib.Instance
import com.squareup.haha.perflib.RootObj
import com.squareup.haha.perflib.Snapshot
import gnu.trove.THashMap
import gnu.trove.TObjectProcedure
import leakcanary.AnalyzerProgressListener
import leakcanary.AnalyzerProgressListener.Step.BUILDING_LEAK_TRACE
import leakcanary.AnalyzerProgressListener.Step.BUILDING_LEAK_TRACES
import leakcanary.AnalyzerProgressListener.Step.COMPUTING_DOMINATORS
import leakcanary.AnalyzerProgressListener.Step.DEDUPLICATING_GC_ROOTS
import leakcanary.AnalyzerProgressListener.Step.FINDING_LEAKING_REF
import leakcanary.AnalyzerProgressListener.Step.FINDING_LEAKING_REFS
import leakcanary.AnalyzerProgressListener.Step.FINDING_SHORTEST_PATH
import leakcanary.AnalyzerProgressListener.Step.FINDING_SHORTEST_PATHS
import leakcanary.AnalyzerProgressListener.Step.PARSING_HEAP_DUMP
import leakcanary.AnalyzerProgressListener.Step.READING_HEAP_DUMP_FILE
import leakcanary.AnalyzerProgressListener.Step.SCANNING_HEAP_DUMP
import leakcanary.Exclusion
import leakcanary.ExclusionDescription
import leakcanary.HeapAnalysis
import leakcanary.HeapAnalysisException
import leakcanary.HeapAnalysisFailure
import leakcanary.HeapAnalysisSuccess
import leakcanary.HeapDumpMemoryStore
import leakcanary.KeyedWeakReference
import leakcanary.LeakNodeStatus
import leakcanary.LeakNodeStatusAndReason
import leakcanary.LeakTrace
import leakcanary.LeakTraceElement
import leakcanary.LeakTraceElement.Holder
import leakcanary.LeakTraceElement.Holder.ARRAY
import leakcanary.LeakTraceElement.Holder.CLASS
import leakcanary.LeakTraceElement.Holder.OBJECT
import leakcanary.LeakTraceElement.Holder.THREAD
import leakcanary.LeakingInstance
import leakcanary.NoPathToInstance
import leakcanary.PerflibAnalysisResult
import leakcanary.PerflibExcludedRefs
import leakcanary.PerflibHeapDump
import leakcanary.RetainedInstance
import leakcanary.WeakReferenceCleared
import leakcanary.WeakReferenceMissing
import leakcanary.internal.perflib.HahaHelper.asString
import leakcanary.internal.perflib.HahaHelper.asStringArray
import leakcanary.internal.perflib.HahaHelper.classInstanceValues
import leakcanary.internal.perflib.HahaHelper.extendsThread
import leakcanary.internal.perflib.HahaHelper.fieldValue
import leakcanary.internal.perflib.HahaHelper.staticFieldValue
import leakcanary.internal.perflib.HahaHelper.threadName
import leakcanary.internal.perflib.ShortestPathFinder.Result
import org.jetbrains.annotations.TestOnly
import java.util.ArrayList
import java.util.concurrent.TimeUnit.NANOSECONDS

/**
 * Analyzes heap dumps to look for leaks.
 */
class PerflibHeapAnalyzer @TestOnly internal constructor(
  private val listener: AnalyzerProgressListener,
  private val keyedWeakReferenceClassName: String,
  private val heapDumpMemoryStoreClassName: String
) {

  constructor(listener: AnalyzerProgressListener) : this(
      listener, KeyedWeakReference::class.java.name, HeapDumpMemoryStore::class.java.name
  )

  /**
   * Searches the heap dump for a [KeyedWeakReference] instance with the corresponding key,
   * and then computes the shortest strong reference path from that instance to the GC roots.
   */
  @TestOnly
  @Deprecated(
      "Use {@link #checkForLeaks(File, boolean)} instead. We're keeping this only because\n" +
          "    our tests currently run with older heapdumps."
  )
  internal fun checkForLeak(
    heapDump: PerflibHeapDump,
    excludedRefs: PerflibExcludedRefs,
    referenceKey: String
  ): PerflibAnalysisResult {
    val analysisStartNanoTime = System.nanoTime()

    if (!heapDump.heapDumpFile.exists()) {
      val exception = IllegalArgumentException("File does not exist: $heapDump.heapDumpFile")
      return PerflibAnalysisResult.failure(exception, since(analysisStartNanoTime))
    }

    var buffer: MemoryMappedFileBuffer? = null
    try {
      listener.onProgressUpdate(READING_HEAP_DUMP_FILE)
      buffer = MemoryMappedFileBuffer(heapDump.heapDumpFile)
      listener.onProgressUpdate(PARSING_HEAP_DUMP)
      val snapshot = Snapshot.createSnapshot(buffer)
      listener.onProgressUpdate(DEDUPLICATING_GC_ROOTS)
      deduplicateGcRoots(snapshot)
      listener.onProgressUpdate(FINDING_LEAKING_REF)
      val leakingRef =
        findLeakingReference(referenceKey, snapshot) ?: return PerflibAnalysisResult.noLeak(
            "UnknownNoKeyedWeakReference",
            since(analysisStartNanoTime)
        )
      return findLeakTrace(
          heapDump,
          excludedRefs,
          referenceKey, "NAME_NOT_SUPPORTED", analysisStartNanoTime, snapshot,
          leakingRef, 0
      )
    } catch (e: Throwable) {
      return PerflibAnalysisResult.failure(e, since(analysisStartNanoTime))
    } finally {
      try {
        buffer?.dispose()
      } catch (ignored: NoClassDefFoundError) {
        // DirectBuffer does not exist on older Android versions.
      }
    }

  }

  /**
   * Searches the heap dump for a [KeyedWeakReference] instance with the corresponding key,
   * and then computes the shortest strong reference path from that instance to the GC roots.
   */
  fun checkForLeaks(
    heapDump: PerflibHeapDump,
    excludedRefs: PerflibExcludedRefs
  ): HeapAnalysis {
    val analysisStartNanoTime = System.nanoTime()

    if (!heapDump.heapDumpFile.exists()) {
      val exception = IllegalArgumentException("File does not exist: $heapDump.heapDumpFile")
      return HeapAnalysisFailure(
          heapDump.heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
          HeapAnalysisException(exception)
      )
    }

    var buffer: MemoryMappedFileBuffer? = null
    try {
      listener.onProgressUpdate(READING_HEAP_DUMP_FILE)
      buffer = MemoryMappedFileBuffer(heapDump.heapDumpFile)
      listener.onProgressUpdate(SCANNING_HEAP_DUMP)
      val snapshot = Snapshot.createSnapshot(buffer)
      listener.onProgressUpdate(DEDUPLICATING_GC_ROOTS)
      deduplicateGcRoots(snapshot)

      val analysisResults = mutableMapOf<String, RetainedInstance>()

      val (retainedKeys, heapDumpUptimeMillis) = readHeapDumpMemoryStore(snapshot)

      if (retainedKeys.size == 0) {
        val exception = IllegalStateException("No retained keys found in heap dump")
        return HeapAnalysisFailure(
            heapDump.heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
            HeapAnalysisException(exception)
        )
      }

      val leakingWeakRefs =
        findLeakingReferences(snapshot, retainedKeys, analysisResults, heapDumpUptimeMillis)

      val pathResults = findShortestPaths(excludedRefs, snapshot, leakingWeakRefs)

      buildLeakTraces(heapDump, pathResults, snapshot, leakingWeakRefs, analysisResults)

      addRemainingInstancesWithNoPath(leakingWeakRefs, analysisResults)

      return HeapAnalysisSuccess(
          heapDump.heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
          analysisResults.values.toList()
      )
    } catch (exception: Throwable) {
      return HeapAnalysisFailure(
          heapDump.heapDumpFile, System.currentTimeMillis(), since(analysisStartNanoTime),
          HeapAnalysisException(exception)
      )
    } finally {
      try {
        buffer?.dispose()
      } catch (ignored: NoClassDefFoundError) {
        // DirectBuffer does not exist on older Android versions.
      }
    }
  }

  private fun readHeapDumpMemoryStore(snapshot: Snapshot): Pair<MutableList<String>, Long> {
    val heapDumpMemoryStoreClass = snapshot.findClass(heapDumpMemoryStoreClassName)
    val retainedKeysArray =
      staticFieldValue<ArrayInstance>(heapDumpMemoryStoreClass, "retainedKeysForHeapDump")
    val retainedKeys = asStringArray(retainedKeysArray)
    val heapDumpUptimeMillis =
      staticFieldValue<Long>(heapDumpMemoryStoreClass, "heapDumpUptimeMillis")
    return Pair(retainedKeys, heapDumpUptimeMillis)
  }

  /**
   * Pruning duplicates reduces memory pressure from hprof bloat added in Marshmallow.
   */
  internal fun deduplicateGcRoots(snapshot: Snapshot) {
    // THashMap has a smaller memory footprint than HashMap.
    val uniqueRootMap = THashMap<String, RootObj>()

    val gcRoots = snapshot.gcRoots
    for (root in gcRoots) {
      val key = generateRootKey(root)
      if (!uniqueRootMap.containsKey(key)) {
        uniqueRootMap[key] = root
      }
    }

    // Repopulate snapshot with unique GC roots.
    gcRoots.clear()
    uniqueRootMap.forEach(TObjectProcedure { key -> gcRoots.add(uniqueRootMap[key]) })
  }

  private fun generateRootKey(root: RootObj): String {
    return String.format("%s@0x%08x", root.rootType.getName(), root.id)
  }

  private fun findLeakingReferences(
    snapshot: Snapshot,
    retainedKeys: MutableList<String>,
    analysisResults: MutableMap<String, RetainedInstance>,
    heapDumpUptimeMillis: Long
  ): MutableList<HasReferent> {
    listener.onProgressUpdate(FINDING_LEAKING_REFS)

    val refClass = snapshot.findClass(keyedWeakReferenceClassName) ?: throw IllegalStateException(
        "Could not find the "
            + keyedWeakReferenceClassName
            + " class in the heap dump."
    )

    val leakingWeakRefs = mutableListOf<HasReferent>()
    for (weakRef in refClass.instancesList) {
      val weakRefMirror = KeyedWeakReferenceMirror.fromInstance(
          weakRef, heapDumpUptimeMillis
      )

      val wasRetained = retainedKeys.remove(weakRefMirror.key)
      if (wasRetained) {
        if (weakRefMirror is HasReferent) {
          leakingWeakRefs.add(weakRefMirror)
        } else {
          val noLeak = WeakReferenceCleared(
              weakRefMirror.key, weakRefMirror.name, weakRefMirror.className,
              weakRefMirror.watchDurationMillis
          )
          analysisResults[weakRefMirror.key] = noLeak
        }
      }
    }

    retainedKeys.forEach { referenceKey ->
      // This could happen if RefWatcher removed weakly reachable references after providing
      // the set of retained keys
      val noLeak = WeakReferenceMissing(referenceKey)
      analysisResults[referenceKey] = noLeak
    }
    return leakingWeakRefs
  }

  private fun findShortestPaths(
    excludedRefs: PerflibExcludedRefs,
    snapshot: Snapshot,
    leakingWeakRefs: MutableList<HasReferent>
  ): List<Result> {
    listener.onProgressUpdate(FINDING_SHORTEST_PATHS)
    val pathFinder =
      ShortestPathFinder(excludedRefs, ignoreStrings = true)
    return pathFinder.findPaths(snapshot, leakingWeakRefs)
  }

  private fun buildLeakTraces(
    heapDump: PerflibHeapDump,
    pathResults: List<Result>,
    snapshot: Snapshot,
    leakingWeakRefs: MutableList<HasReferent>,
    analysisResults: MutableMap<String, RetainedInstance>
  ) {
    if (heapDump.computeRetainedHeapSize && pathResults.isNotEmpty()) {
      listener.onProgressUpdate(COMPUTING_DOMINATORS)
      // Computing dominators has the side effect of computing retained size.
      snapshot.computeDominators()
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

      val leakTrace = buildLeakTrace(pathResult.leakingNode)

      val retainedSize = if (heapDump.computeRetainedHeapSize) {
        pathResult.leakingNode.instance.totalRetainedSize
      } else {
        null
      }
      val leakDetected = LeakingInstance(
          weakReference.key, weakReference.name, weakReference.className,
          weakReference.watchDurationMillis,
          if (pathResult.excludingKnownLeaks) Exclusion.Status.WONT_FIX_LEAK else null, leakTrace,
          retainedSize
      )
      analysisResults[weakReference.key] = leakDetected
    }
  }

  private fun addRemainingInstancesWithNoPath(
    leakingWeakRefs: MutableList<HasReferent>,
    analysisResults: MutableMap<String, RetainedInstance>
  ) {
    leakingWeakRefs.forEach { refWithNoPath ->
      val noLeak = NoPathToInstance(
          refWithNoPath.key, refWithNoPath.name, refWithNoPath.className,
          refWithNoPath.watchDurationMillis
      )
      analysisResults[refWithNoPath.key] = noLeak
    }
  }

  private fun findLeakingReference(
    key: String,
    snapshot: Snapshot
  ): Instance? {
    val refClass = snapshot.findClass(keyedWeakReferenceClassName) ?: throw IllegalStateException(
        "Could not find the $keyedWeakReferenceClassName class in the heap dump."
    )
    val keysFound = ArrayList<String?>()
    for (instance in refClass.instancesList) {
      val values = classInstanceValues(instance)
      val keyFieldValue = fieldValue<Any>(values, "key")
      if (keyFieldValue == null) {
        keysFound.add(null)
        continue
      }
      val keyCandidate = asString(keyFieldValue)
      if (keyCandidate == key) {
        return fieldValue<Instance>(values, "referent")
      }
      keysFound.add(keyCandidate)
    }
    throw IllegalStateException(
        "Could not find weak reference with key $key in $keysFound"
    )
  }

  private fun findLeakTrace(
    heapDump: PerflibHeapDump,
    excludedRefs: PerflibExcludedRefs,
    referenceKey: String,
    referenceName: String,
    analysisStartNanoTime: Long,
    snapshot: Snapshot,
    leakingRef: Instance,
    watchDurationMs: Long
  ): PerflibAnalysisResult {

    listener.onProgressUpdate(FINDING_SHORTEST_PATH)
    val pathFinder =
      ShortestPathFinder(excludedRefs, ignoreStrings = true)
    val result = pathFinder.findPath(snapshot, leakingRef)

    val className = leakingRef.classObj.className

    // False alarm, no strong reference path to GC Roots.
    if (result.leakingNode == null) {
      return PerflibAnalysisResult.noLeak(className, since(analysisStartNanoTime))
    }

    listener.onProgressUpdate(BUILDING_LEAK_TRACE)
    val leakTrace = buildLeakTrace(result.leakingNode)

    val retainedSize = if (heapDump.computeRetainedHeapSize) {
      listener.onProgressUpdate(COMPUTING_DOMINATORS)
      // Side effect: computes retained size.
      snapshot.computeDominators()

      val leakingInstance = result.leakingNode.instance

      leakingInstance.totalRetainedSize
    } else {
      PerflibAnalysisResult.RETAINED_HEAP_SKIPPED
    }

    return PerflibAnalysisResult.leakDetected(
        referenceKey, referenceName,
        result.excludingKnownLeaks, className, leakTrace,
        retainedSize,
        since(analysisStartNanoTime), watchDurationMs
    )
  }

  private fun buildLeakTrace(
    leakingNode: LeakNode
  ): LeakTrace {
    val elements = ArrayList<LeakTraceElement>()
    // We iterate from the leak to the GC root
    val ignored = leakingNode.instance
    var node: LeakNode? =
      LeakNode(null, ignored, leakingNode, null)
    var leakStatusAndReason = LeakNodeStatus.leaking("It's the leaking instance")
    while (node != null) {
      val element = buildLeakElement(node, leakStatusAndReason)
      if (element != null) {
        elements.add(0, element)
        leakStatusAndReason = if (node.parent?.parent != null) {
          LeakNodeStatus.notLeaking("It's the GC root")
        } else {
          LeakNodeStatus.unknown()
        }
      }
      node = node.parent
    }
    return LeakTrace(elements)
  }

  private fun buildLeakElement(
    node: LeakNode,
    leakStatusAndReason: LeakNodeStatusAndReason
  ): LeakTraceElement? {
    if (node.parent == null) {
      // Ignore any root node.
      return null
    }
    val holder = node.parent.instance

    if (holder is RootObj) {
      return null
    }
    val holderType: Holder
    val className: String
    var extra: String? = null

    className = getClassName(holder)

    val rootClassName = Any::class.java.name
    if (holder is ClassObj) {
      holderType = CLASS
    } else if (holder is ArrayInstance) {
      holderType = ARRAY
    } else {
      val classObj = holder.classObj
      if (extendsThread(classObj)) {
        holderType = THREAD
        val threadName = threadName(holder)
        extra = "(named '$threadName')"
      } else if (className.matches(
              ANONYMOUS_CLASS_NAME_PATTERN.toRegex()
          )
      ) {
        val parentClassName = classObj.superClassObj.className
        if (rootClassName == parentClassName) {
          holderType = OBJECT
          try {
            // This is an anonymous class implementing an interface. The API does not give access
            // to the interfaces implemented by the class. We check if it's in the class path and
            // use that instead.
            val actualClass = Class.forName(classObj.className)
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
    val labels = if (extra == null) emptyList<String>() else mutableListOf(extra)

    val exclusionDescription = node.exclusion?.let {
      ExclusionDescription(node.exclusion.matching, node.exclusion.reason)
    }

    return LeakTraceElement(
        node.leakReference, holderType, className, exclusionDescription, labels, leakStatusAndReason
    )
  }

  private fun getClassName(instance: Instance): String = when (instance) {
    is ClassObj -> instance.className
    is ArrayInstance -> instance.classObj.className
    else -> instance.classObj.className
  }

  private fun since(analysisStartNanoTime: Long): Long {
    return NANOSECONDS.toMillis(System.nanoTime() - analysisStartNanoTime)
  }

  companion object {

    private const val ANONYMOUS_CLASS_NAME_PATTERN = "^.+\\$\\d+$"
  }
}
