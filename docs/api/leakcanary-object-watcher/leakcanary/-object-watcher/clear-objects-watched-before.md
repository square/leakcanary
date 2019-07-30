[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [ObjectWatcher](index.md) / [clearObjectsWatchedBefore](./clear-objects-watched-before.md)

# clearObjectsWatchedBefore

`@Synchronized fun clearObjectsWatchedBefore(heapDumpUptimeMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Clears all [KeyedWeakReference](../-keyed-weak-reference/index.md) that were created before [heapDumpUptimeMillis](clear-objects-watched-before.md#leakcanary.ObjectWatcher$clearObjectsWatchedBefore(kotlin.Long)/heapDumpUptimeMillis) (based on
[clock](../-clock/uptime-millis.md))

