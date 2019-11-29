package leakcanary

import android.app.Application
import leakcanary.AppWatcher.objectWatcher
import leakcanary.internal.InternalAppWatcher
import shark.SharkLog
import java.util.concurrent.TimeUnit

/**
 * The entry point API for using [ObjectWatcher] in an Android app. [AppWatcher.objectWatcher] is
 * in charge of detecting retained objects, and [AppWatcher] is auto configured on app start to
 * pass it activity and fragment instances. Call [ObjectWatcher.watch] on [objectWatcher] to
 * watch any other object that you expect to be unreachable.
 */
object AppWatcher {

  data class Config(
    /**
     * Whether AppWatcher should watch objects (by keeping weak references to them).
     *
     * Default to true in debuggable builds and false is non debuggable builds.
     */
    val enabled: Boolean = InternalAppWatcher.isDebuggableBuild,

    /**
     * Whether AppWatcher should automatically watch destroyed activity instances.
     *
     * Defaults to true.
     */
    val watchActivities: Boolean = true,

    /**
     * Whether AppWatcher should automatically watch destroyed fragment instances.
     *
     * Defaults to true.
     */
    val watchFragments: Boolean = true,

    /**
     * Whether AppWatcher should automatically watch destroyed fragment view instances.
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
   * The current AppWatcher configuration. Can be updated at any time, usually by replacing it with
   * a mutated copy, e.g.:
   *
   * ```
   * LeakCanary.config = LeakCanary.config.copy(enabled = false)
   * ```
   */
  @Volatile
  var config: Config = if (isInstalled) Config() else Config(enabled = false)
    set(newConfig) {
      val previousConfig = field
      field = newConfig
      logConfigChange(previousConfig, newConfig)
    }

  private fun logConfigChange(
    previousConfig: Config,
    newConfig: Config
  ) {
    SharkLog.d {
      val changedFields = mutableListOf<String>()
      Config::class.java.declaredFields.forEach { field ->
        field.isAccessible = true
        val previousValue = field[previousConfig]
        val newValue = field[newConfig]
        if (previousValue != newValue) {
          changedFields += "${field.name}=$newValue"
        }
      }

      val changesInConfig =
        if (changedFields.isNotEmpty()) changedFields.joinToString(", ") else "no changes"

      "Updated AppWatcher.config: Config($changesInConfig)"
    }
  }

  /**
   * The [ObjectWatcher] used by AppWatcher to detect retained objects.
   */
  val objectWatcher
    get() = InternalAppWatcher.objectWatcher

  /** @see [manualInstall] */
  val isInstalled
    get() = InternalAppWatcher.isInstalled

  /**
   * [AppWatcher] is automatically installed on main process start by
   * [leakcanary.internal.AppWatcherInstaller] which is registered in the AndroidManifest.xml of
   * your app. If you disabled [leakcanary.internal.AppWatcherInstaller] or you need AppWatcher
   * or LeakCanary to run outside of the main process then you can call this method to install
   * [AppWatcher].
   */
  fun manualInstall(application: Application) = InternalAppWatcher.install(application)

}