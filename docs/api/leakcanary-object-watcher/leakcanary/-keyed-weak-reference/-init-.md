[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [KeyedWeakReference](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`KeyedWeakReference(referent: `[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`, key: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, watchUptimeMillis: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`, referenceQueue: `[`ReferenceQueue`](https://docs.oracle.com/javase/6/docs/api/java/lang/ref/ReferenceQueue.html)`<`[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`>)`

A weak reference used by [ObjectWatcher](../-object-watcher/index.md) to determine which objects become weakly reachable
and which don't. [ObjectWatcher](../-object-watcher/index.md) uses [key](key.md) to keep track of [KeyedWeakReference](index.md) instances that
haven't made it into the associated [ReferenceQueue](https://docs.oracle.com/javase/6/docs/api/java/lang/ref/ReferenceQueue.html) yet.

[heapDumpUptimeMillis](heap-dump-uptime-millis.md) should be set with the current time from [Clock.uptimeMillis](../-clock/uptime-millis.md) right
before dumping the heap, so that we can later determine how long an object was retained.

