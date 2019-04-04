package com.squareup.leakcanary

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import com.squareup.leakcanary.internal.DisplayLeakActivity
import com.squareup.leakcanary.internal.FragmentRefWatcher
import com.squareup.leakcanary.internal.HeapAnalyzerService
import com.squareup.leakcanary.internal.LeakCanaryInternals

internal object LeakCanaryInternal {

  private var installed = false

  private val clock = object : Clock {
    override fun uptimeMillis(): Long {
      return SystemClock.uptimeMillis()
    }
  }

  val refWatcher = RefWatcher(clock)

  fun install(application: Application) {
    checkMainThread()
    if (installed) {
      throw UnsupportedOperationException("LeakCanary.install() can only be called once")
    }
    installed = true

    if (isInAnalyzerProcess(application)) {
      return
    }

    val heapDumpListener = ServiceHeapDumpListener(application, DisplayLeakService::class.java)

    val debuggerControl = AndroidDebuggerControl()

    val leakDirectoryProvider = LeakCanaryInternals.getLeakDirectoryProvider(application)
    val heapDumper = AndroidHeapDumper(application, leakDirectoryProvider, refWatcher)

    val gcTrigger = GcTrigger.DEFAULT

    val configProvider = { LeakCanary.config }

    val handlerThread = HandlerThread(HeapDumpTrigger.LEAK_CANARY_THREAD_NAME)
    handlerThread.start()
    val backgroundHandler = Handler(handlerThread.looper)

    val trigger = HeapDumpTrigger(
        application, backgroundHandler, debuggerControl, refWatcher, leakDirectoryProvider,
        gcTrigger, heapDumper,
        heapDumpListener, configProvider
    )

    trigger.watchForLeaks()

    LeakCanaryInternals.setEnabledAsync(application, DisplayLeakActivity::class.java, true)
    ActivityRefWatcher.install(application, refWatcher, configProvider)
    FragmentRefWatcher.Helper.install(application, refWatcher, configProvider)
  }

  private fun checkMainThread() {
    if (Looper.getMainLooper().thread !== Thread.currentThread()) {
      throw UnsupportedOperationException(
          "Should be called from the main thread, not ${Thread.currentThread()}"
      )
    }
  }

  /** Returns a string representation of the result of a heap analysis.  */
  fun leakInfo(
    context: Context,
    heapDump: HeapDump,
    result: AnalysisResult,
    detailed: Boolean
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
      info += "* " + result.className!!
      if (result.referenceName != "") {
        info += " (" + result.referenceName + ")"
      }
      info += " has leaked:\n" + result.leakTrace!!.toString() + "\n"
      if (result.retainedHeapSize != AnalysisResult.RETAINED_HEAP_SKIPPED) {
        info += "* Retaining: " + Formatter.formatShortFileSize(
            context, result.retainedHeapSize
        ) + ".\n"
      }
      if (detailed) {
        detailedString = "\n* Details:\n" + result.leakTrace!!.toDetailedString()
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
    if (detailed) {
      detailedString += "* Excluded Refs:\n" + heapDump.excludedRefs
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
   */
  fun isInAnalyzerProcess(context: Context): Boolean {
    var isInAnalyzerProcess: Boolean? = LeakCanaryInternals.isInAnalyzerProcess
    // This only needs to be computed once per process.
    if (isInAnalyzerProcess == null) {
      isInAnalyzerProcess =
        LeakCanaryInternals.isInServiceProcess(context, HeapAnalyzerService::class.java)
      LeakCanaryInternals.isInAnalyzerProcess = isInAnalyzerProcess
    }
    return isInAnalyzerProcess!!
  }
}