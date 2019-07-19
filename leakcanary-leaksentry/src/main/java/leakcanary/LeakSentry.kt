package leakcanary

import android.app.Application
import leakcanary.internal.InternalLeakSentry
import java.util.concurrent.TimeUnit

/**
 * The entry point API for LeakSentry. LeakSentry is in charge of detecting retained objects.
 *
 * LeakSentry can be configured by updating [config]. You can ask LeakSentry to watch any object
 * that you expect to be unreachable by calling [ObjectWatcher.watch] on [objectWatcher].
 */
object LeakSentry {

  data class Config(
    /**
     * Whether LeakSentry should watch objects (by keeping weak references to them).
     *
     * Default to true in debuggable builds and false is non debuggable builds.
     */
    val enabled: Boolean = InternalLeakSentry.isDebuggableBuild,

    /**
     * Whether LeakSentry should automatically watch destroyed activity instances.
     *
     * Defaults to true.
     */
    val watchActivities: Boolean = true,

    /**
     * Whether LeakSentry should automatically watch destroyed fragment instances.
     *
     * Defaults to true.
     */
    val watchFragments: Boolean = true,

    /**
     * Whether LeakSentry should automatically watch destroyed fragment view instances.
     *
     * Defaults to true.
     */
    val watchFragmentViews: Boolean = true,

    /**
     * How long to wait before reporting a watched object as retained.
     *
     * Default to 5 seconds.
     */
    val watchDurationMillis: Long = TimeUnit.SECONDS.toMillis(5)
  )

  /**
   * The current LeakSentry configuration. Can be updated at any time, usually by replacing it with
   * a mutated copy, e.g.:
   *
   * ```
   * LeakCanary.config = LeakCanary.config.copy(enabled = false)
   * ```
   */
  @Volatile
  var config: Config = if (isInstalled) Config() else Config(enabled = false)

  /**
   * The [ObjectWatcher] used by LeakSentry to detect retained objects.
   */
  val objectWatcher
    get() = InternalLeakSentry.objectWatcher

  /** @see [manualInstall] */
  val isInstalled
    get() = InternalLeakSentry.isInstalled

  /**
   * [LeakSentry] is automatically installed on main process start by
   * [leakcanary.internal.LeakSentryInstaller] which is registered in the AndroidManifest.xml of
   * your app. If you disabled [leakcanary.internal.LeakSentryInstaller] or you need LeakSentry
   * or LeakCanary to run outside of the main process then you can call this method to install
   * [LeakSentry].
   */
  fun manualInstall(application: Application) = InternalLeakSentry.install(application)

}