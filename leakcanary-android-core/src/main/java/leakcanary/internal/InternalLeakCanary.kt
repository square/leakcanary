package leakcanary.internal

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Context.UI_MODE_SERVICE
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.content.pm.PackageManager.DONT_KILL_APP
import android.content.pm.ShortcutInfo.Builder
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Handler
import android.os.HandlerThread
import com.squareup.leakcanary.core.BuildConfig
import com.squareup.leakcanary.core.R
import leakcanary.AppWatcher
import leakcanary.GcTrigger
import leakcanary.LeakCanary
import leakcanary.OnObjectRetainedListener
import leakcanary.internal.HeapDumpControl.ICanHazHeap.Nope
import leakcanary.internal.HeapDumpControl.ICanHazHeap.Yup
import leakcanary.internal.InternalLeakCanary.FormFactor.MOBILE
import leakcanary.internal.InternalLeakCanary.FormFactor.TV
import leakcanary.internal.InternalLeakCanary.FormFactor.WATCH
import leakcanary.internal.tv.TvOnRetainInstanceListener
import shark.SharkLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

internal object InternalLeakCanary : (Application) -> Unit, OnObjectRetainedListener {

  private const val DYNAMIC_SHORTCUT_ID = "com.squareup.leakcanary.dynamic_shortcut"

  private lateinit var heapDumpTrigger: HeapDumpTrigger

  // You're wrong https://discuss.kotlinlang.org/t/object-or-top-level-property-name-warning/6621/7
  @Suppress("ObjectPropertyName")
  private var _application: Application? = null

  val application: Application
    get() {
      check(_application != null) {
        "LeakCanary not installed, see AppWatcher.manualInstall()"
      }
      return _application!!
    }

  // BuildConfig.LIBRARY_VERSION is stripped so this static var is how we keep it around to find
  // it later when parsing the heap dump.
  @Suppress("unused")
  @JvmStatic
  private var version = BuildConfig.LIBRARY_VERSION

  @Volatile
  var applicationVisible = false
    private set

  private val isDebuggableBuild by lazy {
    (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
  }

  fun createLeakDirectoryProvider(context: Context): LeakDirectoryProvider {
    val appContext = context.applicationContext
    return LeakDirectoryProvider(appContext, {
      LeakCanary.config.maxStoredHeapDumps
    }, {
      LeakCanary.config.requestWriteExternalStoragePermission
    })
  }

  internal enum class FormFactor {
    MOBILE,
    TV,
    WATCH,
  }

  val formFactor by lazy {
    val currentModeType =
      (application.getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType
    return@lazy when (currentModeType) {
      Configuration.UI_MODE_TYPE_TELEVISION -> TV
      Configuration.UI_MODE_TYPE_WATCH -> WATCH
      else -> MOBILE
    }
  }

  val isInstantApp by lazy {
    VERSION.SDK_INT >= VERSION_CODES.O && application.packageManager.isInstantApp
  }

  val onRetainInstanceListener by lazy {
    when (formFactor) {
      TV -> TvOnRetainInstanceListener(application)
      else -> DefaultOnRetainInstanceListener()
    }
  }

  var resumedActivity: Activity? = null

  override fun invoke(application: Application) {
    _application = application

    checkRunningInDebuggableBuild()

    AppWatcher.objectWatcher.addOnObjectRetainedListener(this)

    val heapDumper = AndroidHeapDumper(application, createLeakDirectoryProvider(application))

    val gcTrigger = GcTrigger.Default

    val configProvider = { LeakCanary.config }

    val handlerThread = HandlerThread(LEAK_CANARY_THREAD_NAME)
    handlerThread.start()
    val backgroundHandler = Handler(handlerThread.looper)

    heapDumpTrigger = HeapDumpTrigger(
        application, backgroundHandler, AppWatcher.objectWatcher, gcTrigger, heapDumper,
        configProvider
    )
    application.registerVisibilityListener { applicationVisible ->
      this.applicationVisible = applicationVisible
      heapDumpTrigger.onApplicationVisibilityChanged(applicationVisible)
    }
    registerResumedActivityListener(application)
    addDynamicShortcut(application)

    // We post so that the log happens after Application.onCreate()
    Handler().post {
      SharkLog.d {
        when (val iCanHasHeap = HeapDumpControl.iCanHasHeap()) {
          is Yup -> application.getString(R.string.leak_canary_heap_dump_enabled_text)
          is Nope -> application.getString(
              R.string.leak_canary_heap_dump_disabled_text, iCanHasHeap.reason()
          )
        }
      }
    }
  }

  private fun checkRunningInDebuggableBuild() {
    if (isDebuggableBuild) {
      return
    }

    if (!application.resources.getBoolean(R.bool.leak_canary_allow_in_non_debuggable_build)) {
      throw Error(
          """
        LeakCanary in non-debuggable build
        
        LeakCanary should only be used in debug builds, but this APK is not debuggable.
        Please follow the instructions on the "Getting started" page to only include LeakCanary in
        debug builds: https://square.github.io/leakcanary/getting_started/
        
        If you're sure you want to include LeakCanary in a non-debuggable build, follow the 
        instructions here: https://square.github.io/leakcanary/recipes/#leakcanary-in-release-builds
      """.trimIndent()
      )
    }
  }

  private fun registerResumedActivityListener(application: Application) {
    application.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks by noOpDelegate() {
      override fun onActivityResumed(activity: Activity) {
        resumedActivity = activity
      }

      override fun onActivityPaused(activity: Activity) {
        if (resumedActivity === activity) {
          resumedActivity = null
        }
      }
    })
  }

  @Suppress("ReturnCount")
  private fun addDynamicShortcut(application: Application) {
    if (VERSION.SDK_INT < VERSION_CODES.N_MR1) {
      return
    }
    if (!application.resources.getBoolean(R.bool.leak_canary_add_dynamic_shortcut)) {
      return
    }
    if (isInstantApp) {
      // Instant Apps don't have access to ShortcutManager
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

  override fun onObjectRetained() = scheduleRetainedObjectCheck()

  fun scheduleRetainedObjectCheck() {
    if (this::heapDumpTrigger.isInitialized) {
      heapDumpTrigger.scheduleRetainedObjectCheck()
    }
  }

  fun onDumpHeapReceived(forceDump: Boolean) {
    if (this::heapDumpTrigger.isInitialized) {
      heapDumpTrigger.onDumpHeapReceived(forceDump)
    }
  }

  fun setEnabledBlocking(
    componentClassName: String,
    enabled: Boolean
  ) {
    val component = ComponentName(application, componentClassName)
    val newState =
      if (enabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED
    // Blocks on IPC.
    application.packageManager.setComponentEnabledSetting(component, newState, DONT_KILL_APP)
  }

  inline fun <reified T : Any> noOpDelegate(): T {
    val javaClass = T::class.java
    val noOpHandler = InvocationHandler { _, _, _ ->
      // no op
    }
    return Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(javaClass), noOpHandler
    ) as T
  }

  private const val LEAK_CANARY_THREAD_NAME = "LeakCanary-Heap-Dump"
}
