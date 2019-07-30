[leakcanary-android-core](../../index.md) / [leakcanary](../index.md) / [LeakCanary](index.md) / [config](./config.md)

# config

`var config: `[`LeakCanary.Config`](-config/index.md)

The current LeakCanary configuration. Can be updated at any time, usually by replacing it with
a mutated copy, e.g.:

```
LeakCanary.config = LeakCanary.config.copy(computeRetainedHeapSize = true)
```

