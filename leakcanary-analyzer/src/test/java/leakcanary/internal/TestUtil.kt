package leakcanary.internal

import leakcanary.AnalyzerProgressListener
import leakcanary.Exclusion
import leakcanary.Exclusion.ExclusionType.InstanceFieldExclusion
import leakcanary.Exclusion.ExclusionType.JavaLocalExclusion
import leakcanary.Exclusion.Status.NEVER_REACHABLE
import leakcanary.Exclusion.Status.WEAKLY_REACHABLE
import leakcanary.HeapAnalysis
import leakcanary.HeapAnalysisFailure
import leakcanary.HeapAnalyzer
import leakcanary.KeyedWeakReference
import leakcanary.LeakTraceInspector
import java.io.File
import java.lang.ref.PhantomReference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference

@Suppress("UNCHECKED_CAST")
fun <T : HeapAnalysis> File.checkForLeaks(
  leakTraceInspectors: List<LeakTraceInspector> = emptyList(),
  computeRetainedHeapSize: Boolean = false,
  exclusions: List<Exclusion> = defaultExclusionsFactory
): T {
  val heapAnalyzer = HeapAnalyzer(AnalyzerProgressListener.NONE)
  val result = heapAnalyzer.checkForLeaks(
      this, exclusions, computeRetainedHeapSize, leakTraceInspectors
  )
  if (result is HeapAnalysisFailure) {
    println(result)
  }
  return result as T
}

val defaultExclusionsFactory: List<Exclusion> =
  listOf(
      Exclusion(
          type = InstanceFieldExclusion(WeakReference::class.java.name, "referent"),
          status = WEAKLY_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion(KeyedWeakReference::class.java.name, "referent"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion(SoftReference::class.java.name, "referent"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion(PhantomReference::class.java.name, "referent"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("java.lang.ref.Finalizer", "prev"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("java.lang.ref.Finalizer", "element"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("java.lang.ref.Finalizer", "next"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("java.lang.ref.FinalizerReference", "prev"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("java.lang.ref.FinalizerReference", "element"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("java.lang.ref.FinalizerReference", "next"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("sun.misc.Cleaner", "prev"), status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("sun.misc.Cleaner", "next"), status = NEVER_REACHABLE
      )
      ,

      Exclusion(
          type = JavaLocalExclusion("FinalizerWatchdogDaemon"),
          status = NEVER_REACHABLE
      ),
      Exclusion(
          type = JavaLocalExclusion("main"),
          status = NEVER_REACHABLE
      )
  )