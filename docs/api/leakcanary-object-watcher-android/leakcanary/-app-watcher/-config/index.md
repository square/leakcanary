[leakcanary-object-watcher-android](../../../index.md) / [leakcanary](../../index.md) / [AppWatcher](../index.md) / [Config](./index.md)

# Config

`data class Config`

AppWatcher configuration data class. Properties can be updated via [copy](#).

**See Also**

[config](../config.md)

### Types

| Name | Summary |
|---|---|
| [Builder](-builder/index.md) | `class Builder`<br>Builder for [Config](./index.md) intended to be used only from Java code. |

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `Config(watchActivities: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, watchFragments: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, watchFragmentViews: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, watchViewModels: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, watchDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = TimeUnit.SECONDS.toMillis(5), enabled: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true)`<br>AppWatcher configuration data class. Properties can be updated via [copy](#). |

### Properties

| Name | Summary |
|---|---|
| [enabled](enabled.md) | `val ~~enabled~~: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Deprecated, this didn't need to be a part of the API. Used to indicate whether AppWatcher should watch objects (by keeping weak references to them). Currently a no-op. |
| [watchActivities](watch-activities.md) | `val watchActivities: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether AppWatcher should automatically watch destroyed activity instances. |
| [watchDurationMillis](watch-duration-millis.md) | `val watchDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>How long to wait before reporting a watched object as retained. |
| [watchFragments](watch-fragments.md) | `val watchFragments: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether AppWatcher should automatically watch destroyed fragment instances. |
| [watchFragmentViews](watch-fragment-views.md) | `val watchFragmentViews: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether AppWatcher should automatically watch destroyed fragment view instances. |
| [watchViewModels](watch-view-models.md) | `val watchViewModels: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether AppWatcher should automatically watch cleared [androidx.lifecycle.ViewModel](#) instances. |

### Functions

| Name | Summary |
|---|---|
| [newBuilder](new-builder.md)<br>(Kotlin 999.9) | `fun newBuilder(): `[`AppWatcher.Config.Builder`](-builder/index.md)<br>Construct a new Config via [AppWatcher.Config.Builder](-builder/index.md). Note: this method is intended to be used from Java code only. For idiomatic Kotlin use `copy()` to modify [AppWatcher.config](../config.md). |
