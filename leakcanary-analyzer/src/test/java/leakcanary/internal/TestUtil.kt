package leakcanary.internal

import leakcanary.AnalyzerProgressListener
import leakcanary.AndroidObjectInspectors
import leakcanary.HeapAnalysis
import leakcanary.HeapAnalysisFailure
import leakcanary.HeapAnalyzer
import leakcanary.KeyedWeakReference
import leakcanary.ObjectInspector
import leakcanary.ReferenceMatcher
import leakcanary.ReferenceMatcher.IgnoredReferenceMatcher
import leakcanary.ReferencePattern.InstanceFieldPattern
import leakcanary.ReferencePattern.JavaLocalPattern
import java.io.File
import java.lang.ref.PhantomReference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference

@Suppress("UNCHECKED_CAST")
fun <T : HeapAnalysis> File.checkForLeaks(
  objectInspectors: List<ObjectInspector> = emptyList(),
  computeRetainedHeapSize: Boolean = false,
  referenceMatchers: List<ReferenceMatcher> = defaultReferenceMatchers
): T {
  val inspectors = if (AndroidObjectInspectors.KEYED_WEAK_REFERENCE !in objectInspectors) {
    objectInspectors + AndroidObjectInspectors.KEYED_WEAK_REFERENCE
  } else {
    objectInspectors
  }
  val heapAnalyzer = HeapAnalyzer(AnalyzerProgressListener.NONE)
  val result = heapAnalyzer.checkForLeaks(
      this, referenceMatchers, computeRetainedHeapSize, inspectors
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
          pattern = InstanceFieldPattern(KeyedWeakReference::class.java.name, "referent")
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