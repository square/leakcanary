package shark

import shark.FilteringLeakingObjectFinder.LeakingObjectFilter
import shark.ReferencePattern.InstanceFieldPattern
import shark.ReferencePattern.JavaLocalPattern
import java.io.File
import java.lang.ref.PhantomReference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference

@Suppress("UNCHECKED_CAST")
fun <T : HeapAnalysis> File.checkForLeaks(
  objectInspectors: List<ObjectInspector> = emptyList(),
  computeRetainedHeapSize: Boolean = false,
  referenceMatchers: List<ReferenceMatcher> = defaultReferenceMatchers,
  metadataExtractor: MetadataExtractor = MetadataExtractor.NO_OP,
  proguardMapping: ProguardMapping? = null,
  leakFilters: List<LeakingObjectFilter> = ObjectInspectors.jdkLeakingObjectFilters
): T {
  val inspectors = if (ObjectInspectors.KEYED_WEAK_REFERENCE !in objectInspectors) {
    objectInspectors + ObjectInspectors.KEYED_WEAK_REFERENCE
  } else {
    objectInspectors
  }
  val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
  val result = heapAnalyzer.analyze(
      heapDumpFile = this,
      leakingObjectFinder = FilteringLeakingObjectFinder(leakFilters),
      referenceMatchers = referenceMatchers,
      computeRetainedHeapSize = computeRetainedHeapSize,
      objectInspectors = inspectors,
      metadataExtractor = metadataExtractor,
      proguardMapping = proguardMapping
  )
  if (result is HeapAnalysisFailure) {
    println(result)
  }
  return result as T
}

val defaultReferenceMatchers: List<ReferenceMatcher> =
  listOf(
      IgnoredReferenceMatcher(
          pattern = InstanceFieldPattern(WeakReference::class.java.name, "referent")
      )
      ,
      IgnoredReferenceMatcher(
          pattern = InstanceFieldPattern("leakcanary.KeyedWeakReference", "referent")
      )
      ,
      IgnoredReferenceMatcher(
          pattern = InstanceFieldPattern(SoftReference::class.java.name, "referent")
      )
      ,
      IgnoredReferenceMatcher(
          pattern = InstanceFieldPattern(PhantomReference::class.java.name, "referent")
      )
      ,
      IgnoredReferenceMatcher(
          pattern = InstanceFieldPattern("java.lang.ref.Finalizer", "prev")
      )
      ,
      IgnoredReferenceMatcher(
          pattern = InstanceFieldPattern("java.lang.ref.Finalizer", "element")
      )
      ,
      IgnoredReferenceMatcher(
          pattern = InstanceFieldPattern("java.lang.ref.Finalizer", "next")
      )
      ,
      IgnoredReferenceMatcher(
          pattern = InstanceFieldPattern("java.lang.ref.FinalizerReference", "prev")
      )
      ,
      IgnoredReferenceMatcher(
          pattern = InstanceFieldPattern("java.lang.ref.FinalizerReference", "element")
      )
      ,
      IgnoredReferenceMatcher(
          pattern = InstanceFieldPattern("java.lang.ref.FinalizerReference", "next")
      )
      ,
      IgnoredReferenceMatcher(
          pattern = InstanceFieldPattern("sun.misc.Cleaner", "prev")
      )
      ,
      IgnoredReferenceMatcher(
          pattern = InstanceFieldPattern("sun.misc.Cleaner", "next")
      )
      ,

      IgnoredReferenceMatcher(
          pattern = JavaLocalPattern("FinalizerWatchdogDaemon")
      ),
      IgnoredReferenceMatcher(
          pattern = JavaLocalPattern("main")
      )
  )