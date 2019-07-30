[leakcanary-object-watcher-android](../../../index.md) / [leakcanary](../../index.md) / [AppWatcher](../index.md) / [Config](./index.md)

# Config

`data class Config`

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `Config(enabled: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = InternalAppWatcher.isDebuggableBuild, watchActivities: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, watchFragments: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, watchFragmentViews: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = true, watchDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)` = TimeUnit.SECONDS.toMillis(5))` |

### Properties

| Name | Summary |
|---|---|
| [enabled](enabled.md) | `val enabled: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether AppWatcher should watch objects (by keeping weak references to them). |
| [watchActivities](watch-activities.md) | `val watchActivities: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether AppWatcher should automatically watch destroyed activity instances. |
| [watchDurationMillis](watch-duration-millis.md) | `val watchDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>How long to wait before reporting a watched object as retained. |
| [watchFragments](watch-fragments.md) | `val watchFragments: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether AppWatcher should automatically watch destroyed fragment instances. |
| [watchFragmentViews](watch-fragment-views.md) | `val watchFragmentViews: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether AppWatcher should automatically watch destroyed fragment view instances. |
