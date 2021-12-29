package leakcanary

import java.io.File
import shark.ConstantMemoryMetricsDualSourceProvider
import shark.FileSourceProvider
import shark.HeapAnalysis
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.HprofHeapGraph
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.LeakingObjectFinder
import shark.MetadataExtractor
import shark.ObjectInspector
import shark.OnAnalysisProgressListener
import shark.ProguardMapping
import shark.ReferenceMatcher

/**
 * Sets up [HeapAnalyzer] for instrumentation tests and delegates heap analysis.
 */
class InstrumentationHeapAnalyzer(
  val leakingObjectFinder: LeakingObjectFinder,
  val referenceMatchers: List<ReferenceMatcher>,
  val computeRetainedHeapSize: Boolean,
  val metadataExtractor: MetadataExtractor,
  val objectInspectors: List<ObjectInspector>,
  val proguardMapping: ProguardMapping?
) {

  fun analyze(heapDumpFile: File): HeapAnalysis {
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)

    val sourceProvider = ConstantMemoryMetricsDualSourceProvider(FileSourceProvider(heapDumpFile))

    val closeableGraph = try {
      sourceProvider.openHeapGraph(proguardMapping)
    } catch (throwable: Throwable) {
      return HeapAnalysisFailure(
        heapDumpFile = heapDumpFile,
        createdAtTimeMillis = System.currentTimeMillis(),
        analysisDurationMillis = 0,
        exception = HeapAnalysisException(throwable)
      )
    }
    return closeableGraph
      .use { graph ->
        val result = heapAnalyzer.analyze(
          heapDumpFile = heapDumpFile,
          graph = graph,
          leakingObjectFinder = leakingObjectFinder,
          referenceMatchers = referenceMatchers,
          computeRetainedHeapSize = computeRetainedHeapSize,
          objectInspectors = objectInspectors,
          metadataExtractor = metadataExtractor
        )
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
}
