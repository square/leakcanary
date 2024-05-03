package leakcanary


import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo.Builder
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import com.squareup.leakcanary.core.R
import shark.SharkLog

internal object LeakCanaryAndroidInternalUtils {

  private const val DYNAMIC_SHORTCUT_ID = "com.squareup.leakcanary.dynamic_shortcut"

  @Suppress("ReturnCount")
  fun addLeakActivityDynamicShortcut(application: Application) {
    if (VERSION.SDK_INT < VERSION_CODES.N_MR1) {
      return
    }
    if (!application.resources.getBoolean(R.bool.leak_canary_add_dynamic_shortcut)) {
      return
    }
    if (isInstantApp(application)) {
      // Instant Apps don't have access to ShortcutManager
      return
    }
    val shortcutManager = application.getSystemService(ShortcutManager::class.java)
    if (shortcutManager == null) {
      // https://github.com/square/leakcanary/issues/2430
      // ShortcutManager null on Android TV
      return
    }
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
        application.packageManager.getApplicationLabel(application.applicationInfo)
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

    val intent = LeakCanary.newLeakDisplayActivityIntent()
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
      SharkLog.d(ignored) {
        "Could not add dynamic shortcut. " +
          "shortcutCount=$shortcutCount, " +
          "maxShortcutCountPerActivity=${shortcutManager.maxShortcutCountPerActivity}"
      }
    }
  }

  fun isInstantApp(application: Application): Boolean {
    return VERSION.SDK_INT >= VERSION_CODES.O && application.packageManager.isInstantApp
  }
}
