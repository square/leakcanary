package leakcanary

import android.app.Application
import android.os.SystemClock
import leakcanary.AppWatcher.objectWatcher
import leakcanary.internal.LeakCanaryDelegate
import leakcanary.internal.friendly.checkMainThread
import leakcanary.internal.friendly.mainHandler
import leakcanary.internal.isDebuggableBuild
import shark.SharkLog
import java.lang.RuntimeException
import java.util.concurrent.TimeUnit

/**
 * The entry point API for using [ObjectWatcher] in an Android app. [AppWatcher.objectWatcher] is
 * in charge of detecting retained objects, and [AppWatcher] is auto configured on app start to
 * pass it activity and fragment instances. Call [ObjectWatcher.watch] on [objectWatcher] to
 * watch any other object that you expect to be unreachable.
 */
object AppWatcher {

  private const val RETAINED_DELAY_NOT_SET = -1L

  @Volatile
  private var retainedDelayMillis = RETAINED_DELAY_NOT_SET

  private var installCause: Exception? = null

  /**
   * The [ObjectWatcher] used by AppWatcher to detect retained objects.
   * Only set when [isInstalled] is true.
   */
  val objectWatcher = ObjectWatcher(
    clock = { SystemClock.uptimeMillis() },
    checkRetainedExecutor = {
      check(isInstalled) {
        "AppWatcher not installed"
      }
      mainHandler.postDelayed(it, retainedDelayMillis)
    },
    isEnabled = { true }
  )

  /** @see [manualInstall] */
  val isInstalled: Boolean
    get() = installCause != null

  /**
   * Enables usage of [AppWatcher.objectWatcher] which will expect passed in objects to become
   * weakly reachable within [retainedDelayMillis] ms and if not will trigger LeakCanary (if
   * LeakCanary is in the classpath).
   *
   * In the main process, this method is automatically called with default parameter values  on app
   * startup. You can call this method directly to customize the installation, however you must
   * first disable the automatic call by overriding the `leak_canary_watcher_auto_install` boolean
   * resource:
   *
   * ```
   * <?xml version="1.0" encoding="utf-8"?>
   * <resources>
   *   <bool name="leak_canary_watcher_auto_install">false</bool>
   * </resources>
   * ```
   *
   * [watchersToInstall] can be customized to a subset of the default app watchers:
   *
   * ```
   * val watchersToInstall = AppWatcher.appDefaultWatchers(application)
   *   .filter { it !is RootViewWatcher }
   * AppWatcher.manualInstall(
   *   application = application,
   *   watchersToInstall = watchersToInstall
   * )
   * ```
   *
   * [watchersToInstall] can also be customized to ignore specific instances (e.g. here ignoring
   * leaks of BadSdkLeakingFragment):
   *
   * ```
   * val watchersToInstall = AppWatcher.appDefaultWatchers(application, ReachabilityWatcher { watchedObject, description ->
   *   if (watchedObject !is BadSdkLeakingFragment) {
   *     AppWatcher.objectWatcher.expectWeaklyReachable(watchedObject, description)
   *   }
   * })
   * AppWatcher.manualInstall(
   *   application = application,
   *   watchersToInstall = watchersToInstall
   * )
   * ```
   */
  @JvmOverloads
  fun manualInstall(
    application: Application,
    retainedDelayMillis: Long = TimeUnit.SECONDS.toMillis(5),
    watchersToInstall: List<InstallableWatcher> = appDefaultWatchers(application)
  ) {
    checkMainThread()
    if (isInstalled) {
      throw IllegalStateException(
        "AppWatcher already installed, see exception cause for prior install call", installCause
      )
    }
    check(retainedDelayMillis >= 0) {
      "retainedDelayMillis $retainedDelayMillis must be at least 0 ms"
    }
    installCause = RuntimeException("manualInstall() first called here")
    this.retainedDelayMillis = retainedDelayMillis
    if (application.isDebuggableBuild) {
      LogcatSharkLog.install()
    }
    // Requires AppWatcher.objectWatcher to be set
    LeakCanaryDelegate.loadLeakCanary(application)

    watchersToInstall.forEach {
      it.install()
    }
  }

  /**
   * Creates a new list of default app [InstallableWatcher], created with the passed in
   * [reachabilityWatcher] (which defaults to [objectWatcher]). Once installed,
   * these watchers will pass in to [reachabilityWatcher] objects that they expect to become
   * weakly reachable.
   *
   * The passed in [reachabilityWatcher] should probably delegate to [objectWatcher] but can
   * be used to filter out specific instances.
   */
  fun appDefaultWatchers(
    application: Application,
    reachabilityWatcher: ReachabilityWatcher = objectWatcher
  ): List<InstallableWatcher> {
    return listOf(
      ActivityWatcher(application, reachabilityWatcher),
      FragmentAndViewModelWatcher(application, reachabilityWatcher),
      RootViewWatcher(reachabilityWatcher),
      ServiceWatcher(reachabilityWatcher)
    )
  }

  @Deprecated("Call AppWatcher.manualInstall() ")
  data class Config(
    @Deprecated("Call AppWatcher.manualInstall() with a custom watcher list")
    val watchActivities: Boolean = true,

    @Deprecated("Call AppWatcher.manualInstall() with a custom watcher list")
    val watchFragments: Boolean = true,

    @Deprecated("Call AppWatcher.manualInstall() with a custom watcher list")
    val watchFragmentViews: Boolean = true,

    @Deprecated("Call AppWatcher.manualInstall() with a custom watcher list")
    val watchViewModels: Boolean = true,

    @Deprecated("Call AppWatcher.manualInstall() with a custom retainedDelayMillis value")
    val watchDurationMillis: Long = TimeUnit.SECONDS.toMillis(5),

    @Deprecated("Call AppWatcher.appDefaultWatchers() with a custom ReachabilityWatcher")
    val enabled: Boolean = true
  ) {

    @Deprecated("Configuration moved to AppWatcher.manualInstall()", replaceWith = ReplaceWith(""))
    @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
    @SinceKotlin("999.9") // Hide from Kotlin code, this method is only for Java code
    fun newBuilder(): Builder = Builder(this)

    @Deprecated("Configuration moved to XML resources")
    class Builder internal constructor(config: Config) {
      private var watchActivities = config.watchActivities
      private var watchFragments = config.watchFragments
      private var watchFragmentViews = config.watchFragmentViews
      private var watchViewModels = config.watchViewModels
      private var watchDurationMillis = config.watchDurationMillis

      /** Deprecated. @see [Config.enabled] */
      @Deprecated("see [Config.enabled]", replaceWith = ReplaceWith(""))
      fun enabled(enabled: Boolean) = this

      /** @see [Config.watchActivities] */
      @Deprecated("see [Config.watchActivities]", replaceWith = ReplaceWith(""))
      fun watchActivities(watchActivities: Boolean) =
        apply { this.watchActivities = watchActivities }

      @Deprecated("see [Config.watchFragments]", replaceWith = ReplaceWith(""))
        /** @see [Config.watchFragments] */
      fun watchFragments(watchFragments: Boolean) =
        apply { this.watchFragments = watchFragments }

      @Deprecated("see [Config.watchFragmentViews]", replaceWith = ReplaceWith(""))
        /** @see [Config.watchFragmentViews] */
      fun watchFragmentViews(watchFragmentViews: Boolean) =
        apply { this.watchFragmentViews = watchFragmentViews }

      @Deprecated("see [Config.watchViewModels]", replaceWith = ReplaceWith(""))
        /** @see [Config.watchViewModels] */
      fun watchViewModels(watchViewModels: Boolean) =
        apply { this.watchViewModels = watchViewModels }

      @Deprecated("see [Config.watchDurationMillis]", replaceWith = ReplaceWith(""))
        /** @see [Config.watchDurationMillis] */
      fun watchDurationMillis(watchDurationMillis: Long) =
        apply { this.watchDurationMillis = watchDurationMillis }

      @Deprecated("Configuration moved to AppWatcher.manualInstall()")
      fun build() = config.copy(
        watchActivities = watchActivities,
        watchFragments = watchFragments,
        watchFragmentViews = watchFragmentViews,
        watchViewModels = watchViewModels,
        watchDurationMillis = watchDurationMillis
      )
    }
  }

  @Deprecated("Configuration moved to AppWatcher.manualInstall()")
  @JvmStatic @Volatile
  var config: Config = Config()

}