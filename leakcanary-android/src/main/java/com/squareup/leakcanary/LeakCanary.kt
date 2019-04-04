package com.squareup.leakcanary

import android.content.Context
import com.squareup.leakcanary.internal.HeapAnalyzerService
import com.squareup.leakcanary.internal.InternalLeakCanary

object LeakCanary {

  data class Config(
    val dumpHeap: Boolean = true,
    val excludedRefs: ExcludedRefs = AndroidExcludedRefs.createAppDefaults().build(),
    val reachabilityInspectorClasses: List<Class<out Reachability.Inspector>> = AndroidReachabilityInspectors.defaultAndroidInspectors(),
    val computeRetainedHeapSize: Boolean = false
  )

  @Volatile
  var config: Config = Config()

  /** Returns a string representation of the result of a heap analysis.  */
  fun leakInfo(
    context: Context,
    heapDump: HeapDump,
    result: AnalysisResult,
    detailed: Boolean
  ): String = InternalLeakCanary.leakInfo(context, heapDump, result, detailed)

  /**
   * Whether the current process is the process running the [HeapAnalyzerService], which is
   * a different process than the normal app process.
   */
  fun isInAnalyzerProcess(context: Context): Boolean =
    InternalLeakCanary.isInAnalyzerProcess(context)

}
