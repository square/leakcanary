package leakcanary.internal

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo.Builder
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Handler
import android.os.HandlerThread
import com.squareup.leakcanary.core.R
import leakcanary.CanaryLog
import leakcanary.GcTrigger
import leakcanary.LeakCanary
import leakcanary.LeakSentry

internal object InternalLeakCanary : LeakSentryListener {

  private const val DYNAMIC_SHORTCUT_ID = "com.squareup.leakcanary.dynamic_shortcut"

  private lateinit var heapDumpTrigger: HeapDumpTrigger

  lateinit var application: Application
  @Volatile
  var applicationVisible = false
    private set

  val leakDirectoryProvider: LeakDirectoryProvider by lazy {
    LeakDirectoryProvider(application)
  }

  override fun onLeakSentryInstalled(application: Application) {
    this.application = application

    val heapDumper = AndroidHeapDumper(application, leakDirectoryProvider)

    val gcTrigger = GcTrigger.Default

    val configProvider = { LeakCanary.config }

    val handlerThread = HandlerThread(HeapDumpTrigger.LEAK_CANARY_THREAD_NAME)
    handlerThread.start()
    val backgroundHandler = Handler(handlerThread.looper)

    heapDumpTrigger = HeapDumpTrigger(
        application, backgroundHandler, LeakSentry.refWatcher, gcTrigger, heapDumper, configProvider
    )
    application.registerVisibilityListener { applicationVisible ->
      this.applicationVisible = applicationVisible
      heapDumpTrigger.onApplicationVisibilityChanged(applicationVisible)
    }
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

    val shortcutInstalled =
      dynamicShortcuts.any { shortcut -> shortcut.id == DYNAMIC_SHORTCUT_ID }

    if (shortcutInstalled) {
      return
    }

    val mainIntent = Intent(Intent.ACTION_MAIN, null)
    mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
    mainIntent.setPackage(application.packageName)
    val activities = application.packageManager.queryIntentActivities(mainIntent, 0)
        .filter {
          it.activityInfo.name != "leakcanary.internal.activity.LeakLauncherActivity"
        }

    if (activities.isEmpty()) {
      return
    }

    val firstMainActivity = activities.first()
        .activityInfo

    // Displayed on long tap on app icon
    val longLabel: String
    // Label when dropping shortcut to launcher
    val shortLabel: String

    val leakActivityLabel = application.getString(R.string.leak_canary_shortcut_label)

    if (activities.isEmpty()) {
      longLabel = leakActivityLabel
      shortLabel = leakActivityLabel
    } else {

      val firstLauncherActivityLabel = if (firstMainActivity.labelRes != 0) {
        application.getString(firstMainActivity.labelRes)
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

    val componentName = ComponentName(firstMainActivity.packageName, firstMainActivity.name)

    val shortcutCount = dynamicShortcuts.count { shortcutInfo ->
      shortcutInfo.activity == componentName
    } + shortcutManager.manifestShortcuts.count { shortcutInfo ->
      shortcutInfo.activity == componentName
    }

    if (shortcutCount >= shortcutManager.maxShortcutCountPerActivity) {
      return
    }

    val intent = LeakCanary.createLeakDisplayActivityIntent(application)
    intent.action = "Dummy Action because Android is stupid"
    val shortcut = Builder(application, DYNAMIC_SHORTCUT_ID)
        .setLongLabel(longLabel)
        .setShortLabel(shortLabel)
        .setActivity(componentName)
        .setIcon(Icon.createWithResource(application, R.mipmap.leak_canary_icon))
        .setIntent(intent)
        .build()

    try {
      shortcutManager.addDynamicShortcuts(listOf(shortcut))
    } catch (ignored: Throwable) {
      CanaryLog.d(
          ignored,
          "Could not add dynamic shortcut. " +
              "shortcutCount=$shortcutCount, " +
              "maxShortcutCountPerActivity=${shortcutManager.maxShortcutCountPerActivity}"
      )
    }
  }

  override fun onReferenceRetained() {
    if (this::heapDumpTrigger.isInitialized) {
      heapDumpTrigger.onReferenceRetained()
    }
  }

  fun onDumpHeapReceived() {
    if (this::heapDumpTrigger.isInitialized) {
      heapDumpTrigger.onDumpHeapReceived()
    }
  }
}
