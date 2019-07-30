[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [ObjectWatcher](index.md) / [hasWatchedObjects](./has-watched-objects.md)

# hasWatchedObjects

`val hasWatchedObjects: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

Returns true if there are watched objects that aren't weakly reachable, even
if they haven't been watched for long enough to be considered retained.

