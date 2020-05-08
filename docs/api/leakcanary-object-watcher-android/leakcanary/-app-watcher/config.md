[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [AppWatcher](index.md) / [config](./config.md)

# config

`@JvmStatic var config: `[`AppWatcher.Config`](-config/index.md)

The current AppWatcher configuration. Can be updated at any time, usually by replacing it with
a mutated copy, e.g.:

```
AppWatcher.config = AppWatcher.config.copy(watchFragmentViews = false)
```

In Java, use [AppWatcher.Config.Builder](-config/-builder/index.md) instead:

```
AppWatcher.Config config = AppWatcher.getConfig().newBuilder()
   .watchFragmentViews(false)
   .build();
AppWatcher.setConfig(config);
```

