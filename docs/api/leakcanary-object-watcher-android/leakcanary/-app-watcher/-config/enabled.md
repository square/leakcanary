[leakcanary-object-watcher-android](../../../index.md) / [leakcanary](../../index.md) / [AppWatcher](../index.md) / [Config](index.md) / [enabled](./enabled.md)

# enabled

`val ~~enabled~~: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)
**Deprecated:** This didn't need to be a part of LeakCanary's API. No-Op.

Deprecated, this didn't need to be a part of the API.
Used to indicate whether AppWatcher should watch objects (by keeping weak references to
them). Currently a no-op.

If you do need to stop watching objects, simply don't pass them to [objectWatcher](../object-watcher.md).

