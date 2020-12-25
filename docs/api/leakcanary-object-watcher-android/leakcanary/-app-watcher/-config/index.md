[leakcanary-object-watcher-android](../../../index.md) / [leakcanary](../../index.md) / [AppWatcher](../index.md) / [Config](./index.md)

# Config

`data class ~~Config~~`
**Deprecated:** Call AppWatcher.manualInstall()

### Types

| Name | Summary |
|---|---|
| [Builder](-builder/index.md) | `class ~~Builder~~` |

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `Config(watchActivities: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, watchFragments: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, watchFragmentViews: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, watchViewModels: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, watchDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = TimeUnit.SECONDS.toMillis(5), enabled: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true)` |

### Properties

| Name | Summary |
|---|---|
| [enabled](enabled.md) | `val ~~enabled~~: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [watchActivities](watch-activities.md) | `val ~~watchActivities~~: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [watchDurationMillis](watch-duration-millis.md) | `val ~~watchDurationMillis~~: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
| [watchFragments](watch-fragments.md) | `val ~~watchFragments~~: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [watchFragmentViews](watch-fragment-views.md) | `val ~~watchFragmentViews~~: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [watchViewModels](watch-view-models.md) | `val ~~watchViewModels~~: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |

### Functions

| Name | Summary |
|---|---|
| [newBuilder](new-builder.md)<br>(Kotlin 999.9) | `fun ~~newBuilder~~(): `[`AppWatcher.Config.Builder`](-builder/index.md) |
