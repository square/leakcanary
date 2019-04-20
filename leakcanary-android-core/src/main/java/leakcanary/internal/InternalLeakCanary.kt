package leakcanary.internal

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo.Builder
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Handler
import android.os.HandlerThread
import android.text.format.Formatter
import android.util.Log
import com.squareup.leakcanary.core.BuildConfig
import com.squareup.leakcanary.core.R
import leakcanary.AnalysisResult
import leakcanary.GcTrigger
import leakcanary.HeapDump
import leakcanary.LeakCanary
import leakcanary.LeakSentry
import leakcanary.internal.activity.LeakActivity
import java.lang.Exception

internal object InternalLeakCanary {

  private const val DYNAMIC_SHORTCUT_ID = "com.squareup.leakcanary.dynamic_shortcut"

  private lateinit var heapDumpTrigger: HeapDumpTrigger

  @Volatile private var isInAnalyzerProcess: Boolean? = null

  fun onLeakSentryInstalled(application: Application) {
    if (isInAnalyzerProcess(application)) {
      return
    }
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
        leakDirectoryProvider, gcTrigger, heapDumper, configProvider
    )
    heapDumpTrigger.registerToVisibilityChanges()

    addDynamicShortcut(application)
  }

  private fun addDynamicShortcut(application: Application) {
    if (VERSION.SDK_INT < VERSION_CODES.N_MR1) {
      return
    }
    if (!application.resources.getBoolean(R.bool.leak_canary_add_dynamic_shortcut)) {
      return
    }

    val shortcutManager = application.getSystemService(ShortcutManager::class.java)!!
    val dynamicShortcuts = shortcutManager.dynamicShortcuts

    if (dynamicShortcuts.size >= shortcutManager.maxShortcutCountPerActivity) {
      return
    }

    val shortcutInstalled =
      dynamicShortcuts.any { shortcut -> shortcut.id == DYNAMIC_SHORTCUT_ID }

    if (shortcutInstalled) {
      return
    }

    val mainIntent = Intent(Intent.ACTION_MAIN, null)
    mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
    mainIntent.setPackage(application.packageName)
    val activities = application.packageManager.queryIntentActivities(mainIntent, 0)

    // Displayed on long tap on app icon
    val longLabel: String
    // Label when dropping shortcut to launcher
    val shortLabel: String

    val leakActivityLabel = application.getString(R.string.leak_canary_shortcut_label)

    if (activities.isEmpty()) {
      longLabel = leakActivityLabel
      shortLabel = leakActivityLabel
    } else {

      val activity = activities.first()

      val firstLauncherActivityLabel = if (activity.activityInfo.labelRes != 0) {
        application.getString(activity.activityInfo.labelRes)
      } else {
        val applicationInfo = application.applicationInfo
        if (applicationInfo.labelRes != 0) {
          application.getString(applicationInfo.labelRes)
        } else {
          applicationInfo.nonLocalizedLabel.toString()
        }
      }
      val fullLengthLabel = "$firstLauncherActivityLabel $leakActivityLabel"
      // short label should be under 10 and long label under 25
      if (fullLengthLabel.length > 10) {
        if (fullLengthLabel.length <= 25) {
          longLabel = fullLengthLabel
          shortLabel = leakActivityLabel
        } else {
          longLabel = leakActivityLabel
          shortLabel = leakActivityLabel
        }
      } else {
        longLabel = fullLengthLabel
        shortLabel = fullLengthLabel
      }
    }

    val intent = LeakActivity.createIntent(application)
    intent.action = "Dummy Action because Android is stupid"
    val shortcut = Builder(application, DYNAMIC_SHORTCUT_ID)
        .setLongLabel(longLabel)
        .setShortLabel(shortLabel)
        .setIcon(Icon.createWithResource(application, R.mipmap.leak_canary_icon))
        .setIntent(intent)
        .build()
    shortcutManager.addDynamicShortcuts(listOf(shortcut))
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
  @Deprecated("Remove and build better rendering for new data structures.")
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
   * Whether the current process is the process running the perflib heap analyzer, which is
   * a different process than the normal app process.
   *
   * Note: We can't rely on [Application] being set here as [onLeakSentryInstalled] is called after
   * [Application.onCreate]
   */
  fun isInAnalyzerProcess(context: Context): Boolean {
    val analyzerServiceClass: Class<out Service>
    @Suppress("UNCHECKED_CAST")
    try {
      analyzerServiceClass =
        Class.forName("leakcanary.internal.perflib.PerflibHeapAnalyzer") as Class<out Service>
    } catch (e: Exception) {
      return false
    }

    var isInAnalyzerProcess: Boolean? = isInAnalyzerProcess
    // This only needs to be computed once per process.
    if (isInAnalyzerProcess == null) {
      isInAnalyzerProcess =
        LeakCanaryUtils.isInServiceProcess(context, analyzerServiceClass)
      this.isInAnalyzerProcess = isInAnalyzerProcess
    }
    return isInAnalyzerProcess
  }
}