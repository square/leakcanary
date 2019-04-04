package leaksentry

import android.app.Application
import leaksentry.internal.InternalLeakSentry
import java.util.concurrent.TimeUnit

object LeakSentry {

  data class Config(
    val watchActivities: Boolean = true,
    val watchFragments: Boolean = true,
    val watchFragmentViews: Boolean = true,
    val watchDurationMillis: Long = TimeUnit.SECONDS.toMillis(5)
  )

  @Volatile
  var config: Config = Config()

  val refWatcher
    get() = InternalLeakSentry.refWatcher

  /**
   * [LeakSentry] is automatically installed on process start by
   * [leaksentry.internal.LeakSentryInstaller] which is registered in the AndroidManifest.xml of
   * your app. If you disabled [leaksentry.internal.LeakSentryInstaller] then you can call this
   * method to install [LeakSentry].
   */
  fun manualInstall(application: Application) = InternalLeakSentry.install(application)

}