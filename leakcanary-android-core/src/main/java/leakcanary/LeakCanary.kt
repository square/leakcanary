package leakcanary

import android.content.Intent
import leakcanary.LeakCanary.config
import leakcanary.internal.InternalLeakCanary
import shark.AndroidMetadataExtractor
import shark.AndroidObjectInspectors
import shark.AndroidReferenceMatchers
import shark.HeapAnalysisSuccess
import shark.IgnoredReferenceMatcher
import shark.LibraryLeakReferenceMatcher
import shark.MetadataExtractor
import shark.ObjectInspector
import shark.ReferenceMatcher
import shark.SharkLog

/**
 * The entry point API for LeakCanary. LeakCanary builds on top of [AppWatcher]. AppWatcher
 * notifies LeakCanary of retained instances, which in turns dumps the heap, analyses it and
 * publishes the results.
 *
 * LeakCanary can be configured by updating [config].
 */
object LeakCanary {

  /**
   * LeakCanary configuration data class. Properties can be updated via [copy].
   *
   * @see [config]
   */
  data class Config(
    /**
     * Whether LeakCanary should dump the heap when enough retained instances are found. This needs
     * to be true for LeakCanary to work, but sometimes you may want to temporarily disable
     * LeakCanary (e.g. for a product demo).
     *
     * Defaults to true.
     */
    val dumpHeap: Boolean = true,
    /**
     * If [dumpHeapWhenDebugging] is false then LeakCanary will not dump the heap
     * when the debugger is attached. The debugger can create temporary memory leaks (for instance
     * if a thread is blocked on a breakpoint).
     *
     * Defaults to false.
     */
    val dumpHeapWhenDebugging: Boolean = false,
    /**
     * When the app is visible, LeakCanary will wait for at least
     * [retainedVisibleThreshold] retained instances before dumping the heap. Dumping the heap
     * freezes the UI and can be frustrating for developers who are trying to work. This is
     * especially frustrating as the Android Framework has a number of leaks that cannot easily
     * be fixed.
     *
     * When the app becomes invisible, LeakCanary dumps the heap after
     * [AppWatcher.Config.watchDurationMillis] ms.
     *
     * The app is considered visible if it has at least one activity in started state.
     *
     * A higher threshold means LeakCanary will dump the heap less often, therefore it won't be
     * bothering developers as much but it could miss some leaks.
     *
     * Defaults to 5.
     */
    val retainedVisibleThreshold: Int = 5,

    /**
     * Known patterns of references in the heap, lister here either to ignore them
     * ([IgnoredReferenceMatcher]) or to mark them as library leaks ([LibraryLeakReferenceMatcher]).
     *
     * When adding your own custom [LibraryLeakReferenceMatcher] instances, you'll most
     * likely want to set [LibraryLeakReferenceMatcher.patternApplies] with a filter that checks
     * for the Android OS version and manufacturer. The build information can be obtained by calling
     * [shark.AndroidBuildMirror.fromHeapGraph].
     *
     * Defaults to [AndroidReferenceMatchers.appDefaults]
     */
    val referenceMatchers: List<ReferenceMatcher> = AndroidReferenceMatchers.appDefaults,

    /**
     * List of [ObjectInspector] that provide LeakCanary with insights about objects found in the
     * heap. You can create your own [ObjectInspector] implementations, and also add
     * a [shark.AppSingletonInspector] instance created with the list of internal singletons.
     *
     * Defaults to [AndroidObjectInspectors.appDefaults]
     */
    val objectInspectors: List<ObjectInspector> = AndroidObjectInspectors.appDefaults,

    /**
     * Called on a background thread when the heap analysis is complete.
     * If you want leaks to be added to the activity that lists leaks, make sure to delegate
     * calls to a [DefaultOnHeapAnalyzedListener].
     *
     * Defaults to [DefaultOnHeapAnalyzedListener]
     */
    val onHeapAnalyzedListener: OnHeapAnalyzedListener = DefaultOnHeapAnalyzedListener.create(),

    /**
     * Extracts metadata from a hprof to be reported in [HeapAnalysisSuccess.metadata].
     * Called on a background thread during heap analysis.
     *
     * Defaults to [AndroidMetadataExtractor]
     */
    val metatadaExtractor: MetadataExtractor = AndroidMetadataExtractor,

    /**
     * Whether to compute the retained heap size, which is the total number of bytes in memory that
     * would be reclaimed if the detected leaks didn't happen. This includes native memory
     * associated to Java objects (e.g. Android bitmaps).
     *
     * Computing the retained heap size can slow down the analysis because it requires navigating
     * from GC roots through the entire object graph, whereas [shark.HeapAnalyzer] would otherwise
     * stop as soon as all leaking instances are found.
     *
     * Defaults to true.
     */
    val computeRetainedHeapSize: Boolean = true,

    /**
     * How many heap dumps are kept on the Android device for this app package. When this threshold
     * is reached LeakCanary deletes the older heap dumps. As several heap dumps may be enqueued
     * you should avoid going down to 1 or 2.
     *
     * Defaults to 7.
     */
    val maxStoredHeapDumps: Int = 7,

    /**
     * LeakCanary always attempts to store heap dumps on the external storage if the
     * WRITE_EXTERNAL_STORAGE is already granted, and otherwise uses the app storage.
     * If the WRITE_EXTERNAL_STORAGE permission is not granted and
     * [requestWriteExternalStoragePermission] is true, then LeakCanary will display a notification
     * to ask for that permission.
     *
     * Defaults to false because that permission notification can be annoying.
     */
    val requestWriteExternalStoragePermission: Boolean = false,

    /**
     * When true, [objectInspectors] are used to find leaks instead of only checking instances
     * tracked by [KeyedWeakReference]. This leads to finding more leaks and shorter leak traces.
     * However this also means the same leaking instances will be found in every heap dump for a
     * given process life.
     *
     * Defaults to false.
     */
    val useExperimentalLeakFinders: Boolean = false
  )

  /**
   * The current LeakCanary configuration. Can be updated at any time, usually by replacing it with
   * a mutated copy, e.g.:
   *
   * ```
   * LeakCanary.config = LeakCanary.config.copy(computeRetainedHeapSize = true)
   * ```
   */
  @Volatile
  var config: Config = if (AppWatcher.isInstalled) Config() else InternalLeakCanary.noInstallConfig
    set(newConfig) {
      val previousConfig = field
      field = newConfig
      logConfigChange(previousConfig, newConfig)
    }

  private fun logConfigChange(
    previousConfig: Config,
    newConfig: Config
  ) {
    SharkLog.d {
      val changedFields = mutableListOf<String>()
      Config::class.java.declaredFields.forEach { field ->
        field.isAccessible = true
        val previousValue = field[previousConfig]
        val newValue = field[newConfig]
        if (previousValue != newValue) {
          changedFields += "${field.name}=$newValue"
        }
      }
      val changesInConfig =
        if (changedFields.isNotEmpty()) changedFields.joinToString(", ") else "no changes"

      "Updated LeakCanary.config: Config($changesInConfig)"
    }
  }

  /**
   * Returns a new [Intent] that can be used to programmatically launch the leak display activity.
   */
  fun newLeakDisplayActivityIntent() = InternalLeakCanary.leakDisplayActivityIntent

  /**
   * Dynamically shows / hides the launcher icon for the leak display activity.
   * Note: you can change the default value by overriding the leak_canary_add_launcher_icon
   * boolean resource:
   *
   * ```
   * <?xml version="1.0" encoding="utf-8"?>
   * <resources>
   *   <bool name="leak_canary_add_launcher_icon">false</bool>
   * </resources>
   * ```
   */
  fun showLeakDisplayActivityLauncherIcon(showLauncherIcon: Boolean) {
    InternalLeakCanary.setEnabledBlocking(
        "leakcanary.internal.activity.LeakLauncherActivity", showLauncherIcon
    )
  }

  /**
   * Immediately triggers a heap dump and analysis, if there is at least one retained instance
   * tracked by [AppWatcher.objectWatcher]. If there are no retained instances then the heap will not
   * be dumped and a notification will be shown instead.
   */
  fun dumpHeap() = InternalLeakCanary.onDumpHeapReceived(forceDump = true)
}
