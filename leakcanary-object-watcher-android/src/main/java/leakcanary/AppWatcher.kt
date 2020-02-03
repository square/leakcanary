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

  /**
   * AppWatcher configuration data class. Properties can be updated via [copy].
   *
   * @see [config]
   */
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
     * Whether AppWatcher should automatically watch cleared [androidx.lifecycle.ViewModel]
     * instances.
     *
     * Defaults to true.
     */
    val watchViewModels: Boolean = true,

    /**
     * How long to wait before reporting a watched object as retained.
     *
     * Default to 5 seconds.
     */
    val watchDurationMillis: Long = TimeUnit.SECONDS.toMillis(5)
  ) {

    /**
     * Construct a new Config via [AppWatcher.Config.Builder].
     * Note: this method is intended to be used from Java code only. For idiomatic Kotlin use
     * `copy()` to modify [AppWatcher.config].
     */
    @Suppress("NEWER_VERSION_IN_SINCE_KOTLIN")
    @SinceKotlin("999.9") // Hide from Kotlin code, this method is only for Java code
    fun newBuilder(): Builder = Builder(this)

    /**
     * Builder for [Config] intended to be used only from Java code.
     *
     * Usage:
     * ```
     * AppWatcher.Config config = AppWatcher.getConfig().newBuilder()
     *    .watchFragmentViews(false)
     *    .build();
     * AppWatcher.setConfig(config);
     * ```
     *
     * For idiomatic Kotlin use `copy()` method instead:
     * ```
     * AppWatcher.config = AppWatcher.config.copy(watchFragmentViews = false)
     * ```
     */
    @Suppress("TooManyFunctions")
    class Builder internal constructor(config: Config) {
      private var enabled = config.enabled
      private var watchActivities = config.watchActivities
      private var watchFragments = config.watchFragments
      private var watchFragmentViews = config.watchFragmentViews
      private var watchViewModels = config.watchViewModels
      private var watchDurationMillis = config.watchDurationMillis

      /** @see [Config.enabled] */
      fun enabled(enabled: Boolean) =
        apply { this.enabled = enabled }

      /** @see [Config.watchActivities] */
      fun watchActivities(watchActivities: Boolean) =
        apply { this.watchActivities = watchActivities }

      /** @see [Config.watchFragments] */
      fun watchFragments(watchFragments: Boolean) =
        apply { this.watchFragments = watchFragments }

      /** @see [Config.watchFragmentViews] */
      fun watchFragmentViews(watchFragmentViews: Boolean) =
        apply { this.watchFragmentViews = watchFragmentViews }

      /** @see [Config.watchViewModels] */
      fun watchViewModels(watchViewModels: Boolean) =
        apply { this.watchViewModels = watchViewModels }

      /** @see [Config.watchDurationMillis] */
      fun watchDurationMillis(watchDurationMillis: Long) =
        apply { this.watchDurationMillis = watchDurationMillis }

      fun build() = config.copy(
          enabled = enabled,
          watchActivities = watchActivities,
          watchFragments = watchFragments,
          watchFragmentViews = watchFragmentViews,
          watchViewModels = watchViewModels,
          watchDurationMillis = watchDurationMillis
      )
    }
  }

  /**
   * The current AppWatcher configuration. Can be updated at any time, usually by replacing it with
   * a mutated copy, e.g.:
   *
   * ```
   * AppWatcher.config = AppWatcher.config.copy(watchFragmentViews = false)
   * ```
   *
   * In Java, you can use [AppWatcher.Config.Builder] instead:
   * ```
   * AppWatcher.Config config = AppWatcher.getConfig().newBuilder()
   *    .watchFragmentViews(false)
   *    .build();
   * AppWatcher.setConfig(config);
   * ```
   */
  @JvmStatic @Volatile
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