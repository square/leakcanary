package leakcanary

import android.app.Application
import android.os.SystemClock
import java.util.concurrent.TimeUnit
import leakcanary.AppWatcher.objectWatcher
import leakcanary.internal.LeakCanaryDelegate
import leakcanary.internal.friendly.checkMainThread
import leakcanary.internal.friendly.mainHandler
import leakcanary.internal.isDebuggableBuild

/**
 * The entry point API for using [ObjectWatcher] in an Android app. [AppWatcher.objectWatcher] is
 * in charge of detecting retained objects, and [AppWatcher] is auto configured on app start to
 * pass it activity and fragment instances. Call [ObjectWatcher.watch] on [objectWatcher] to
 * watch any other object that you expect to be unreachable.
 */
object AppWatcher {

  private const val RETAINED_DELAY_NOT_SET = -1L

  @Volatile
  var retainedDelayMillis = RETAINED_DELAY_NOT_SET
    private set

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
   * ```xml
   * <?xml version="1.0" encoding="utf-8"?>
   * <resources>
   *   <bool name="leak_canary_watcher_auto_install">false</bool>
   * </resources>
   * ```
   *
   * [watchersToInstall] can be customized to a subset of the default app watchers:
   *
   * ```kotlin
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
   * ```kotlin
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
    this.retainedDelayMillis = retainedDelayMillis
    if (application.isDebuggableBuild) {
      LogcatSharkLog.install()
    }
    // Requires AppWatcher.objectWatcher to be set
    LeakCanaryDelegate.loadLeakCanary(application)

    watchersToInstall.forEach {
      it.install()
    }
    // Only install after we're fully done with init.
    installCause = RuntimeException("manualInstall() first called here")
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
}
