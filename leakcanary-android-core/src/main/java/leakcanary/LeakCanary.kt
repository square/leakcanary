package leakcanary

import android.content.Context
import leakcanary.internal.InternalLeakCanary

object LeakCanary {

  data class Config(
    val dumpHeap: Boolean = true,
    val excludedRefs: ExcludedRefs = AndroidExcludedRefs.createAppDefaults().build(),
    val reachabilityInspectorClasses: List<Class<out Reachability.Inspector>> = AndroidReachabilityInspectors.defaultAndroidInspectors(),
    /**
     * Note: this is currently not implemented in the new heap parser.
     */
    val computeRetainedHeapSize: Boolean = false
  )

  @Volatile
  var config: Config = Config()

  /**
   * Whether the current process is the process running
   * [leakcanary.internal.HeapAnalyzerServiceHeapAnalyzerService], which is a different process than the normal app process.
   */
  @Deprecated("This always returns false when using the new parser.")
  fun isInAnalyzerProcess(context: Context): Boolean =
    InternalLeakCanary.isInAnalyzerProcess(context)

}
