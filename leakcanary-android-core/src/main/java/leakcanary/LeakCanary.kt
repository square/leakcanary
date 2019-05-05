package leakcanary

import android.app.Application
import leakcanary.AndroidExcludedRefs.Companion.exclusionsFactory
import leakcanary.internal.InternalLeakCanary

object LeakCanary {

  data class Config(
    val dumpHeap: Boolean = true,
    /**
     * The debugger can create temporary memory leaks (for instance if a thread is blocked on a
     * breakpoint) so by default LeakCanary does not dump the heap when the debugger is attached.
     */
    val dumpHeapWhenDebugging: Boolean = false,
    /**
     * When the app is visible, LeakCanary will wait for at least
     * [retainedVisibleThreshold] retained instances before dumping the heap. Dumping the heap
     * freezes the UI and can be frustrating for developers who are trying to work. This is
     * especially frustrating as the Android Framework has a number of leaks that cannot easily
     * be fixed.
     *
     * When the app becomes invisible, LeakCanary dumps the heap immediately.
     *
     * A higher threshold means LeakCanary will dump the heap less often, therefore it won't be
     * bothering developers as much but it could miss some leaks.
     */
    val retainedVisibleThreshold: Int = 5,
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
    val analysisResultListener: (Application, HeapAnalysis) -> Unit = DefaultAnalysisResultListener
  )

  @Volatile
  var config: Config = Config()
}
