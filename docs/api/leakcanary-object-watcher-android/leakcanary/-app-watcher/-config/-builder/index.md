[leakcanary-object-watcher-android](../../../../index.md) / [leakcanary](../../../index.md) / [AppWatcher](../../index.md) / [Config](../index.md) / [Builder](./index.md)

# Builder

`class Builder`

Builder for [Config](../index.md) intended to be used only from Java code.

Usage:

```
AppWatcher.Config config = AppWatcher.getConfig().newBuilder()
   .watchFragmentViews(false)
   .build();
AppWatcher.setConfig(config);
```

For idiomatic Kotlin use `copy()` method instead:

```
AppWatcher.config = AppWatcher.config.copy(watchFragmentViews = false)
```

### Functions

| Name | Summary |
|---|---|
| [build](build.md) | `fun build(): `[`AppWatcher.Config`](../index.md) |
| [enabled](enabled.md) | `fun ~~enabled~~(enabled: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`): `[`AppWatcher.Config.Builder`](./index.md)<br>Deprecated. @see [Config.enabled](../enabled.md) |
| [watchActivities](watch-activities.md) | `fun watchActivities(watchActivities: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`): `[`AppWatcher.Config.Builder`](./index.md) |
| [watchDurationMillis](watch-duration-millis.md) | `fun watchDurationMillis(watchDurationMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`AppWatcher.Config.Builder`](./index.md) |
| [watchFragments](watch-fragments.md) | `fun watchFragments(watchFragments: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`): `[`AppWatcher.Config.Builder`](./index.md) |
| [watchFragmentViews](watch-fragment-views.md) | `fun watchFragmentViews(watchFragmentViews: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`): `[`AppWatcher.Config.Builder`](./index.md) |
| [watchViewModels](watch-view-models.md) | `fun watchViewModels(watchViewModels: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`): `[`AppWatcher.Config.Builder`](./index.md) |
