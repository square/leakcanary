package shark

import java.io.File
import shark.HprofHeapGraph.Companion.openHeapGraph

fun <T : HeapAnalysis> DualSourceProvider.checkForLeaks(
  objectInspectors: List<ObjectInspector> = emptyList(),
  computeRetainedHeapSize: Boolean = false,
  referenceMatchers: List<ReferenceMatcher> = JdkReferenceMatchers.defaults,
  metadataExtractor: MetadataExtractor = MetadataExtractor.NO_OP,
  proguardMapping: ProguardMapping? = null,
  leakingObjectFinder: LeakingObjectFinder = FilteringLeakingObjectFinder(
    ObjectInspectors.jdkLeakingObjectFilters
  ),
  file: File = File("/no/file")
): T {
  val inspectors = if (ObjectInspectors.KEYED_WEAK_REFERENCE !in objectInspectors) {
    objectInspectors + ObjectInspectors.KEYED_WEAK_REFERENCE
  } else {
    objectInspectors
  }
  val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)

  val result = openHeapGraph(proguardMapping).use { graph ->
    heapAnalyzer.analyze(
      heapDumpFile = file,
      graph = graph,
      leakingObjectFinder = leakingObjectFinder,
      referenceMatchers = referenceMatchers,
      computeRetainedHeapSize = computeRetainedHeapSize,
      objectInspectors = inspectors,
      metadataExtractor = metadataExtractor,
    )
  }
  if (result is HeapAnalysisFailure) {
    println(result)
  }
  @Suppress("UNCHECKED_CAST")
  return result as T
}

fun <T : HeapAnalysis> File.checkForLeaks(
  objectInspectors: List<ObjectInspector> = emptyList(),
  computeRetainedHeapSize: Boolean = false,
  referenceMatchers: List<ReferenceMatcher> = JdkReferenceMatchers.defaults,
  metadataExtractor: MetadataExtractor = MetadataExtractor.NO_OP,
  proguardMapping: ProguardMapping? = null,
  leakingObjectFinder: LeakingObjectFinder = FilteringLeakingObjectFinder(
    ObjectInspectors.jdkLeakingObjectFilters
  ),
): T {
  return FileSourceProvider(this).checkForLeaks(
    objectInspectors, computeRetainedHeapSize, referenceMatchers, metadataExtractor,
    proguardMapping, leakingObjectFinder, this
  )
}
