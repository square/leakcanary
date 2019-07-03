package leakcanary

import android.app.Application
import android.content.Intent
import leakcanary.internal.InternalLeakCanary

typealias AnalysisResultListener = (Application, HeapAnalysis) -> Unit

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

    val knownReferences: Set<AndroidKnownReference> = AndroidKnownReference.appDefaults,

    val leakTraceInspectors: List<LeakTraceInspector> = AndroidLeakTraceInspectors.defaultInspectors(),

    /**
     * Called with the heap analysis result from a background thread.
     * The heap dump file will be removed immediately after this function is invoked.
     * If you want leaks to be added to the activity that lists leaks, make sure to delegate
     * calls to [DefaultAnalysisResultListener].
     */
    val analysisResultListener: AnalysisResultListener = DefaultAnalysisResultListener,
    /**
     * Whether to compute the total number of bytes in memory that would be reclaimed if the
     * detected leaks didn't happen. This includes native memory associated to Java objects
     * (e.g. bitmaps).
     * Computing the retained heap size can slow down the leak analysis and is off by default.
     */
    val computeRetainedHeapSize: Boolean = false,

    /**
     * How many heap dumps are kept locally. When this threshold is reached LeakCanary starts
     * deleting the older heap dumps. As several heap dumps may be enqueued you should avoid
     * going down to 1 or 2.
     */
    val maxStoredHeapDumps: Int = 7,

    /**
     * LeakCanary always attempts to store heap dumps on the external storage first. If the
     * WRITE_EXTERNAL_STORAGE permission is not granted and [requestWriteExternalStoragePermission]
     * is true, then LeakCanary will display a notification to ask for that permission.
     */
    val requestWriteExternalStoragePermission: Boolean = false

  )

  @Volatile
  var config: Config = if (LeakSentry.isInstalled) Config() else InternalLeakCanary.noInstallConfig

  /** [Intent] that can be used to programmatically launch the leak display activity. */
  val leakDisplayActivityIntent
    get() = InternalLeakCanary.leakDisplayActivityIntent
}
