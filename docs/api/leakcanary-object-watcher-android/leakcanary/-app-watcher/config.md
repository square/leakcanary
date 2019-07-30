[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [AppWatcher](index.md) / [config](./config.md)

# config

`var config: `[`AppWatcher.Config`](-config/index.md)

The current AppWatcher configuration. Can be updated at any time, usually by replacing it with
a mutated copy, e.g.:

```
LeakCanary.config = LeakCanary.config.copy(enabled = false)
```

