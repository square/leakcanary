//[leakcanary-object-watcher](../../../index.md)/[leakcanary](../index.md)/[ObjectWatcher](index.md)/[clearObjectsWatchedBefore](clear-objects-watched-before.md)

# clearObjectsWatchedBefore

[jvm]\

@[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)

fun [clearObjectsWatchedBefore](clear-objects-watched-before.md)(heapDumpUptimeMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html))

Clears all [KeyedWeakReference](../-keyed-weak-reference/index.md) that were created before [heapDumpUptimeMillis](clear-objects-watched-before.md) (based on [clock](../-clock/uptime-millis.md))
