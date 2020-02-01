package leakcanary.appwatcherconfig

import leakcanary.AppWatcher
import leakcanary.AppWatcher.Config

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
class ConfigBuilder(config: Config) {
  private var enabled: Boolean = config.enabled
  private var watchActivities: Boolean = config.watchActivities
  private var watchFragments: Boolean = config.watchFragments
  private var watchFragmentViews: Boolean = config.watchFragmentViews
  private var watchViewModels: Boolean = config.watchViewModels
  private var watchDurationMillis: Long = config.watchDurationMillis

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

  fun build(): Config =
    AppWatcher.config.copy(
        enabled = enabled,
        watchActivities = watchActivities,
        watchFragments = watchFragments,
        watchFragmentViews = watchFragmentViews,
        watchViewModels = watchViewModels,
        watchDurationMillis = watchDurationMillis
    )
}