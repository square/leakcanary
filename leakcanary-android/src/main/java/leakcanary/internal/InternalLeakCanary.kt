package leakcanary.internal

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.text.format.Formatter
import android.util.Log
import com.squareup.leakcanary.BuildConfig
import leakcanary.AnalysisResult
import leakcanary.GcTrigger
import leakcanary.HeapDump
import leakcanary.LeakCanary
import leakcanary.LeakSentry

internal object InternalLeakCanary {

  private lateinit var heapDumpTrigger: HeapDumpTrigger

  @Volatile private var isInAnalyzerProcess: Boolean? = null

  fun onLeakSentryInstalled(application: Application) {
    if (isInAnalyzerProcess(application)) {
      return
    }
    val heapDumpListener =
      ServiceHeapDumpListener(application, DisplayLeakService::class.java)

    val debuggerControl = AndroidDebuggerControl()

    val leakDirectoryProvider =
      LeakCanaryUtils.getLeakDirectoryProvider(application)
    val heapDumper = AndroidHeapDumper(application, leakDirectoryProvider)

    val gcTrigger = GcTrigger.Default

    val configProvider = { LeakCanary.config }

    val handlerThread = HandlerThread(HeapDumpTrigger.LEAK_CANARY_THREAD_NAME)
    handlerThread.start()
    val backgroundHandler = Handler(handlerThread.looper)

    heapDumpTrigger = HeapDumpTrigger(
        application, backgroundHandler, debuggerControl, LeakSentry.refWatcher,
        leakDirectoryProvider, gcTrigger, heapDumper, heapDumpListener, configProvider
    )
    heapDumpTrigger.registerToVisibilityChanges()
    LeakCanaryUtils.setEnabledAsync(
        application, DisplayLeakActivity::class.java, true
    )
  }

  fun onReferenceRetained() {
    if (this::heapDumpTrigger.isInitialized) {
      heapDumpTrigger.onReferenceRetained()
    }
  }

  /**
   * Returns a string representation of the result of a heap analysis.
   * Context instance needed because [onLeakSentryInstalled] is not called in the leakcanary
   * process.
   */
  fun leakInfo(
    context: Context,
    heapDump: HeapDump,
    result: AnalysisResult
  ): String {
    val packageManager = context.packageManager
    val packageName = context.packageName
    val packageInfo: PackageInfo
    try {
      packageInfo = packageManager.getPackageInfo(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
      throw RuntimeException(e)
    }

    val versionName = packageInfo.versionName
    @Suppress("DEPRECATION")
    val versionCode = packageInfo.versionCode
    var info = "In $packageName:$versionName:$versionCode.\n"
    var detailedString = ""
    if (result.leakFound) {
      if (result.excludedLeak) {
        info += "* EXCLUDED LEAK.\n"
      }
      info += "* ${result.className!!}"
      if (result.referenceName != "") {
        info += " (${result.referenceName})"
      }
      info += " has leaked:\n${result.leakTrace!!.renderToString()}\n"
      if (result.retainedHeapSize != AnalysisResult.RETAINED_HEAP_SKIPPED) {
        info += "* Retaining: " + Formatter.formatShortFileSize(
            context, result.retainedHeapSize
        ) + ".\n"
      }
    } else if (result.failure != null) {
      // We duplicate the library version & Sha information because bug reports often only contain
      // the stacktrace.
      info += "* FAILURE in ${BuildConfig.LIBRARY_VERSION} ${BuildConfig.GIT_SHA}:" + Log.getStackTraceString(
          result.failure
      ) + "\n"
    } else {
      info += "* NO LEAK FOUND.\n\n"
    }

    info += ("* Reference Key: "
        + result.referenceKey
        + "\n"
        + "* Device: "
        + Build.MANUFACTURER
        + " "
        + Build.BRAND
        + " "
        + Build.MODEL
        + " "
        + Build.PRODUCT
        + "\n"
        + "* Android Version: "
        + Build.VERSION.RELEASE
        + " API: "
        + Build.VERSION.SDK_INT
        + " LeakCanary: "
        + BuildConfig.LIBRARY_VERSION
        + " "
        + BuildConfig.GIT_SHA
        + "\n"
        + "* Durations: watch="
        + result.watchDurationMs
        + "ms, gc="
        + heapDump.gcDurationMs
        + "ms, heap dump="
        + heapDump.heapDumpDurationMs
        + "ms, analysis="
        + result.analysisDurationMs
        + "ms"
        + "\n"
        + detailedString)

    return info
  }

  /**
   * Whether the current process is the process running the [HeapAnalyzerService], which is
   * a different process than the normal app process.
   *
   * Note: We can't rely on [Application] being set here as [onLeakSentryInstalled] is called after
   * [Application.onCreate]
   */
  fun isInAnalyzerProcess(context: Context): Boolean {
    var isInAnalyzerProcess: Boolean? = isInAnalyzerProcess
    // This only needs to be computed once per process.
    if (isInAnalyzerProcess == null) {
      isInAnalyzerProcess =
        LeakCanaryUtils.isInServiceProcess(
            context, HeapAnalyzerService::class.java
        )
      this.isInAnalyzerProcess = isInAnalyzerProcess
    }
    return isInAnalyzerProcess
  }
}