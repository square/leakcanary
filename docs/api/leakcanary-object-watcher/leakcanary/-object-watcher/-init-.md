[leakcanary-object-watcher](../../index.md) / [leakcanary](../index.md) / [ObjectWatcher](index.md) / [&lt;init&gt;](./-init-.md)

# &lt;init&gt;

`ObjectWatcher(clock: `[`Clock`](../-clock/index.md)`, checkRetainedExecutor: `[`Executor`](https://docs.oracle.com/javase/6/docs/api/java/util/concurrent/Executor.html)`, isEnabled: () -> `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = { true })`

[ObjectWatcher](index.md) can be passed objects to [watch](watch.md). It will create [KeyedWeakReference](../-keyed-weak-reference/index.md) instances
that reference watches objects, and check if those references have been cleared as expected on
the [checkRetainedExecutor](#) executor. If not, these objects are considered retained and
[ObjectWatcher](index.md) will then notify registered [OnObjectRetainedListener](../-on-object-retained-listener/index.md)s on that executor thread.

[checkRetainedExecutor](#) is expected to run its tasks on a background thread, with a significant
delay to give the GC the opportunity to identify weakly reachable objects.

[ObjectWatcher](index.md) is thread safe.

