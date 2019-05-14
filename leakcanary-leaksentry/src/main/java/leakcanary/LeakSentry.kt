package leakcanary

import android.app.Application
import leakcanary.internal.InternalLeakSentry
import java.util.concurrent.TimeUnit

object LeakSentry {

  data class Config(
    val enabled: Boolean = InternalLeakSentry.isDebuggableBuild,
    val watchActivities: Boolean = true,
    val watchFragments: Boolean = true,
    val watchFragmentViews: Boolean = true,
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