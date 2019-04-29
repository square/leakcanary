package leakcanary

import android.app.Application
import leakcanary.AndroidExcludedRefs.Companion.exclusionsFactory
import leakcanary.internal.InternalLeakCanary

object LeakCanary {

  data class Config(
    val dumpHeap: Boolean = true,
    val exclusionsFactory: (HprofParser) -> List<Exclusion> = exclusionsFactory(
        AndroidExcludedRefs.appDefaults
    ),
    /**
     * Note: this is currently not implemented in the new heap parser.
     */
    val computeRetainedHeapSize: Boolean = false,
    val reachabilityInspectors: List<Reachability.Inspector> = AndroidReachabilityInspectors.defaultAndroidInspectors(),
    val labelers: List<Labeler> = AndroidLabelers.defaultAndroidLabelers(
        InternalLeakCanary.application
    ),
    /**
     * Called with the heap analysis result from a background thread.
     * The heap dump file will be removed immediately after this function is invoked.
     * If you want leaks to be added to the activity that lists leaks, make sure to delegate
     * calls to [DefaultAnalysisResultListener].
     */
    val analysisResultListener: (Application, HeapAnalysis) -> Unit = DefaultAnalysisResultListener()
  )

  @Volatile
  var config: Config = Config()
}
