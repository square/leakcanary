package leakcanary

import leakcanary.internal.InternalLeakCanary

object LeakCanary {

  data class Config(
    val dumpHeap: Boolean = true,
    val excludedRefs: ExcludedRefs = AndroidExcludedRefs.createAppDefaults().build(),
    val reachabilityInspectorClasses: List<Class<out Reachability.Inspector>> = AndroidReachabilityInspectors.defaultAndroidInspectors(),
    val labelers: List<Labeler> = AndroidLabelers.defaultAndroidLabelers(
        InternalLeakCanary.application
    ),
    /**
     * Note: this is currently not implemented in the new heap parser.
     */
    val computeRetainedHeapSize: Boolean = false
  )

  @Volatile
  var config: Config = Config()
}
