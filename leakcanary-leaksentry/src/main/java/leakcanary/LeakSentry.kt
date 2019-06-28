package leakcanary

import android.app.Application
import leakcanary.internal.InternalLeakSentry
import java.util.concurrent.TimeUnit

object LeakSentry {

  data class Config(
    /**
     * Whether LeakSentry should watch instances (by keeping weak references to them). Default is
     * true in debuggable builds and false is non debuggable builds.
     */
    val enabled: Boolean = InternalLeakSentry.isDebuggableBuild,
    /**
     * Whether LeakCanary should automatically watch destroyed activities.
     */
    val watchActivities: Boolean = true,
    /**
     * Whether LeakCanary should automatically watch destroyed fragments.
     */
    val watchFragments: Boolean = true,
    /**
     * Whether LeakCanary should automatically watch destroyed fragment views.
     */
    val watchFragmentViews: Boolean = true,
    /**
     * How long to wait before reporting a watched instance as retained. Default is 5 seconds.
     */
    val watchDurationMillis: Long = TimeUnit.SECONDS.toMillis(5)
  )

  @Volatile
  var config: Config = if (isInstalled) Config() else Config(enabled = false)

  val refWatcher
    get() = InternalLeakSentry.refWatcher

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