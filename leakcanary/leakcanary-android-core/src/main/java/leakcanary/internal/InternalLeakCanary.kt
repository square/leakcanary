package leakcanary.internal

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Context.UI_MODE_SERVICE
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.content.pm.PackageManager.DONT_KILL_APP
import android.content.res.Configuration
import android.os.Handler
import android.os.HandlerThread
import com.squareup.leakcanary.core.BuildConfig
import com.squareup.leakcanary.core.R
import leakcanary.AppWatcher
import leakcanary.EventListener.Event
import leakcanary.GcTrigger
import leakcanary.LeakCanary
import leakcanary.LeakCanaryAndroidInternalUtils
import leakcanary.OnObjectRetainedListener
import leakcanary.inProcess
import leakcanary.internal.HeapDumpControl.ICanHazHeap.Nope
import leakcanary.internal.HeapDumpControl.ICanHazHeap.Yup
import leakcanary.internal.InternalLeakCanary.FormFactor.MOBILE
import leakcanary.internal.InternalLeakCanary.FormFactor.TV
import leakcanary.internal.InternalLeakCanary.FormFactor.WATCH
import leakcanary.internal.friendly.mainHandler
import leakcanary.internal.friendly.noOpDelegate
import leakcanary.internal.tv.TvOnRetainInstanceListener
import shark.SharkLog

internal object InternalLeakCanary : (Application) -> Unit, OnObjectRetainedListener {


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
    return@lazy when ((application.getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType) {
      Configuration.UI_MODE_TYPE_TELEVISION -> TV
      Configuration.UI_MODE_TYPE_WATCH -> WATCH
      else -> MOBILE
    }
  }

  val isInstantApp by lazy {
    LeakCanaryAndroidInternalUtils.isInstantApp(application)
  }

  val onRetainInstanceListener by lazy {
    when (formFactor) {
      TV -> TvOnRetainInstanceListener(application)
      else -> DefaultOnRetainInstanceListener()
    }
  }

  var resumedActivity: Activity? = null

  private val heapDumpPrefs by lazy {
    application.getSharedPreferences("LeakCanaryHeapDumpPrefs", Context.MODE_PRIVATE)
  }

  internal var dumpEnabledInAboutScreen: Boolean
    get() {
      return heapDumpPrefs
        .getBoolean("AboutScreenDumpEnabled", true)
    }
    set(value) {
      heapDumpPrefs
        .edit()
        .putBoolean("AboutScreenDumpEnabled", value)
        .apply()
    }

  override fun invoke(application: Application) {
    _application = application

    checkRunningInDebuggableBuild()

    AppWatcher.objectWatcher.addOnObjectRetainedListener(this)

    val gcTrigger = GcTrigger.inProcess()

    val configProvider = { LeakCanary.config }

    val handlerThread = HandlerThread(LEAK_CANARY_THREAD_NAME)
    handlerThread.start()
    val backgroundHandler = Handler(handlerThread.looper)

    heapDumpTrigger = HeapDumpTrigger(
      application, backgroundHandler, AppWatcher.objectWatcher, gcTrigger,
      configProvider
    )
    application.registerVisibilityListener { applicationVisible ->
      this.applicationVisible = applicationVisible
      heapDumpTrigger.onApplicationVisibilityChanged(applicationVisible)
    }
    registerResumedActivityListener(application)
    LeakCanaryAndroidInternalUtils.addLeakActivityDynamicShortcut(application)

    // We post so that the log happens after Application.onCreate() where
    // the config could be updated.
    mainHandler.post {
      // https://github.com/square/leakcanary/issues/1981
      // We post to a background handler because HeapDumpControl.iCanHasHeap() checks a shared pref
      // which blocks until loaded and that creates a StrictMode violation.
      backgroundHandler.post {
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

  fun sendEvent(event: Event) {
    for(listener in LeakCanary.config.eventListeners) {
      listener.onEvent(event)
    }
  }

  private const val LEAK_CANARY_THREAD_NAME = "LeakCanary-Heap-Dump"
}
