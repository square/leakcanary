package leakcanary.internal

import leakcanary.AnalyzerProgressListener
import leakcanary.CanaryLog
import leakcanary.Exclusion
import leakcanary.Exclusion.ExclusionType.InstanceFieldExclusion
import leakcanary.Exclusion.ExclusionType.ThreadExclusion
import leakcanary.Exclusion.Status.NEVER_REACHABLE
import leakcanary.Exclusion.Status.WEAKLY_REACHABLE
import leakcanary.ExclusionsFactory
import leakcanary.LeakInspector
import leakcanary.HeapAnalysis
import leakcanary.HeapAnalysisFailure
import leakcanary.HeapAnalyzer
import leakcanary.HprofParser
import leakcanary.KeyedWeakReference
import leakcanary.Labeler
import java.io.File
import java.lang.ref.PhantomReference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference

@Suppress("UNCHECKED_CAST")
fun <T : HeapAnalysis> File.checkForLeaks(
  labelers: List<Labeler> = emptyList(),
  leakInspectors: List<LeakInspector> = emptyList(),
  computeRetainedHeapSize: Boolean = false,
  exclusionsFactory: ExclusionsFactory = defaultExclusionsFactory
): T {
  val heapAnalyzer = HeapAnalyzer(AnalyzerProgressListener.NONE)
  return heapAnalyzer.checkForLeaks(
      this, exclusionsFactory, computeRetainedHeapSize, leakInspectors, labelers
  ) as T
}

val defaultExclusionsFactory: ExclusionsFactory = {
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
          type = ThreadExclusion("FinalizerWatchdogDaemon"),
          status = NEVER_REACHABLE
      ),
      Exclusion(
          type = ThreadExclusion("main"),
          status = NEVER_REACHABLE
      )
  )
}