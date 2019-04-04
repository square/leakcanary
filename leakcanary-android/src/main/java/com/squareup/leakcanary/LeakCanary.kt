package com.squareup.leakcanary

import android.app.Application
import android.content.Context
import com.squareup.leakcanary.internal.HeapAnalyzerService

object LeakCanary {

  data class Config(
    val watchActivities: Boolean = true,
    val watchFragments: Boolean = true,
    val watchFragmentViews: Boolean = true,
    val dumpHeap: Boolean = true,
    val excludedRefs: ExcludedRefs = AndroidExcludedRefs.createAppDefaults().build(),
    val reachabilityInspectorClasses: List<Class<out Reachability.Inspector>> = AndroidReachabilityInspectors.defaultAndroidInspectors(),
    val computeRetainedHeapSize: Boolean = false
  )

  @Volatile
  var config: Config = Config()

  val refWatcher
    get() = LeakCanaryInternal.refWatcher

  /** Returns a string representation of the result of a heap analysis.  */
  fun leakInfo(
    context: Context,
    heapDump: HeapDump,
    result: AnalysisResult,
    detailed: Boolean
  ): String = LeakCanaryInternal.leakInfo(context, heapDump, result, detailed)

  /**
   * Whether the current process is the process running the [HeapAnalyzerService], which is
   * a different process than the normal app process.
   */
  fun isInAnalyzerProcess(context: Context): Boolean =
    LeakCanaryInternal.isInAnalyzerProcess(context)

  /**
   * LeakCanary is automatically installed on process start with [LeakCanaryInstaller] which is
   * automatically registered in the AndroidManifest.xml of your app. If you disabled
   * [LeakCanaryInstaller] then you can call this method to install LeakCanary.
   */
  fun manualInstall(application: Application) = LeakCanaryInternal.install(application)

}
