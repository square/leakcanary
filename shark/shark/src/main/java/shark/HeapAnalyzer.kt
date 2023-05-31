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

import java.io.File
import java.util.concurrent.TimeUnit.NANOSECONDS
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.OnAnalysisProgressListener.Step.BUILDING_LEAK_TRACES
import shark.OnAnalysisProgressListener.Step.COMPUTING_NATIVE_RETAINED_SIZE
import shark.OnAnalysisProgressListener.Step.COMPUTING_RETAINED_SIZE
import shark.OnAnalysisProgressListener.Step.EXTRACTING_METADATA
import shark.OnAnalysisProgressListener.Step.FINDING_DOMINATORS
import shark.OnAnalysisProgressListener.Step.FINDING_PATHS_TO_RETAINED_OBJECTS
import shark.OnAnalysisProgressListener.Step.FINDING_RETAINED_OBJECTS
import shark.OnAnalysisProgressListener.Step.INSPECTING_OBJECTS
import shark.OnAnalysisProgressListener.Step.PARSING_HEAP_DUMP
import shark.PrioritizingShortestPathFinder.Event.StartedFindingDominators
import shark.PrioritizingShortestPathFinder.Event.StartedFindingPathsToRetainedObjects
import shark.RealLeakTracerFactory.Event.StartedBuildingLeakTraces
import shark.RealLeakTracerFactory.Event.StartedComputingJavaHeapRetainedSize
import shark.RealLeakTracerFactory.Event.StartedComputingNativeRetainedSize
import shark.RealLeakTracerFactory.Event.StartedInspectingObjects

/**
 * Analyzes heap dumps to look for leaks.
 */
class HeapAnalyzer constructor(
  private val listener: OnAnalysisProgressListener
) {

  @Deprecated("Use the non deprecated analyze method instead")
  fun analyze(
    heapDumpFile: File,
    leakingObjectFinder: LeakingObjectFinder,
    referenceMatchers: List<ReferenceMatcher> = emptyList(),
    computeRetainedHeapSize: Boolean = false,
    objectInspectors: List<ObjectInspector> = emptyList(),
    metadataExtractor: MetadataExtractor = MetadataExtractor.NO_OP,
    proguardMapping: ProguardMapping? = null
  ): HeapAnalysis {
    if (!heapDumpFile.exists()) {
      val exception = IllegalArgumentException("File does not exist: $heapDumpFile")
      return HeapAnalysisFailure(
        heapDumpFile = heapDumpFile,
        createdAtTimeMillis = System.currentTimeMillis(),
        analysisDurationMillis = 0,
        exception = HeapAnalysisException(exception)
      )
    }
    listener.onAnalysisProgress(PARSING_HEAP_DUMP)
    val sourceProvider = ConstantMemoryMetricsDualSourceProvider(FileSourceProvider(heapDumpFile))
    return try {
      sourceProvider.openHeapGraph(proguardMapping).use { graph ->
        analyze(
          heapDumpFile,
          graph,
          leakingObjectFinder,
          referenceMatchers,
          computeRetainedHeapSize,
          objectInspectors,
          metadataExtractor
        ).let { result ->
          if (result is HeapAnalysisSuccess) {
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
          } else result
        }
      }
    } catch (throwable: Throwable) {
      HeapAnalysisFailure(
        heapDumpFile = heapDumpFile,
        createdAtTimeMillis = System.currentTimeMillis(),
        analysisDurationMillis = 0,
        exception = HeapAnalysisException(throwable)
      )
    }
  }


  // TODO Callers should add to analysisStartNanoTime
  // Input should be a builder or part of the object state probs?
  // Maybe there's some sort of helper for setting up the right analysis?
  // There's a part focused on finding leaks, and then we add to that.
  // Maybe the result isn't even a leaktrace yet, but something with live object ids?
  // Ideally the result contains only what this can return. No file, etc.
  // File: used to create the graph + in result
  // leakingObjectFinder: Helper object, shared
  // computeRetainedHeapSize: boolean param for single analysis
  // referenceMatchers: param?. though honestly not great.
  // objectInspectors: Helper object.
  // metadataExtractor: helper object, not needed for leak finding
  // referenceReader: can't be helper object, needs graph => param something that can produce it from
  // graph (and in the impl we give that thing the referenceMatchers)
  /**
   * Searches the heap dump for leaking instances and then computes the shortest strong reference
   * path from those instances to the GC roots.
   */
  fun analyze(
    heapDumpFile: File,
    graph: HeapGraph,
    leakingObjectFinder: LeakingObjectFinder,
    referenceMatchers: List<ReferenceMatcher> = emptyList(),
    computeRetainedHeapSize: Boolean = false,
    objectInspectors: List<ObjectInspector> = emptyList(),
    metadataExtractor: MetadataExtractor = MetadataExtractor.NO_OP,
  ): HeapAnalysis {
    val analysisStartNanoTime = System.nanoTime()

    return try {
      val leakTracer = RealLeakTracerFactory(
        shortestPathFinderFactory = PrioritizingShortestPathFinder.Factory(
          listener =  { event ->
            when (event) {
              StartedFindingDominators -> listener.onAnalysisProgress(FINDING_DOMINATORS)
              StartedFindingPathsToRetainedObjects -> listener.onAnalysisProgress(
                FINDING_PATHS_TO_RETAINED_OBJECTS
              )
            }
          },
          referenceReaderFactory = AndroidReferenceReaderFactory(referenceMatchers),
          gcRootProvider = MatchingGcRootProvider(referenceMatchers),
          computeRetainedHeapSize = computeRetainedHeapSize,
        ),
        objectInspectors
      ) { event ->
        when (event) {
          StartedBuildingLeakTraces -> listener.onAnalysisProgress(BUILDING_LEAK_TRACES)
          StartedComputingJavaHeapRetainedSize -> listener.onAnalysisProgress(
            COMPUTING_RETAINED_SIZE
          )

          StartedComputingNativeRetainedSize -> listener.onAnalysisProgress(
            COMPUTING_NATIVE_RETAINED_SIZE
          )

          StartedInspectingObjects -> listener.onAnalysisProgress(INSPECTING_OBJECTS)
        }
      }.createFor(graph)

      listener.onAnalysisProgress(EXTRACTING_METADATA)
      val metadata = metadataExtractor.extractMetadata(graph)

      val retainedClearedWeakRefCount = KeyedWeakReferenceFinder.findKeyedWeakReferences(graph)
        .count { it.isRetained && !it.hasReferent }

      // This should rarely happens, as we generally remove all cleared weak refs right before a heap
      // dump.
      val metadataWithCount = if (retainedClearedWeakRefCount > 0) {
        metadata + ("Count of retained yet cleared" to "$retainedClearedWeakRefCount KeyedWeakReference instances")
      } else {
        metadata
      }

      listener.onAnalysisProgress(FINDING_RETAINED_OBJECTS)
      val leakingObjectIds = leakingObjectFinder.findLeakingObjectIds(graph)

      val (applicationLeaks, libraryLeaks, unreachableObjects) = leakTracer.traceObjects(
        leakingObjectIds
      )

      HeapAnalysisSuccess(
        heapDumpFile = heapDumpFile,
        createdAtTimeMillis = System.currentTimeMillis(),
        analysisDurationMillis = since(analysisStartNanoTime),
        metadata = metadataWithCount,
        applicationLeaks = applicationLeaks,
        libraryLeaks = libraryLeaks,
        unreachableObjects = unreachableObjects
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


  private fun since(analysisStartNanoTime: Long): Long {
    return NANOSECONDS.toMillis(System.nanoTime() - analysisStartNanoTime)
  }
}
