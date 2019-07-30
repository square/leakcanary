[leakcanary-object-watcher](../index.md) / [leakcanary](./index.md)

## Package leakcanary

### Types

| Name | Summary |
|---|---|
| [Clock](-clock/index.md) | `interface Clock`<br>An interface to abstract the SystemClock.uptimeMillis() Android API in non Android artifacts. |
| [GcTrigger](-gc-trigger/index.md) | `interface GcTrigger`<br>[GcTrigger](-gc-trigger/index.md) is used to try triggering garbage collection and enqueuing [KeyedWeakReference](-keyed-weak-reference/index.md) into the associated [java.lang.ref.ReferenceQueue](https://docs.oracle.com/javase/6/docs/api/java/lang/ref/ReferenceQueue.html). The default implementation [Default](-gc-trigger/-default/index.md) comes from AOSP. |
| [KeyedWeakReference](-keyed-weak-reference/index.md) | `class KeyedWeakReference : `[`WeakReference`](https://docs.oracle.com/javase/6/docs/api/java/lang/ref/WeakReference.html)`<`[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`>`<br>A weak reference used by [ObjectWatcher](-object-watcher/index.md) to determine which objects become weakly reachable and which don't. [ObjectWatcher](-object-watcher/index.md) uses [key](-keyed-weak-reference/key.md) to keep track of [KeyedWeakReference](-keyed-weak-reference/index.md) instances that haven't made it into the associated [ReferenceQueue](https://docs.oracle.com/javase/6/docs/api/java/lang/ref/ReferenceQueue.html) yet. |
| [ObjectWatcher](-object-watcher/index.md) | `class ObjectWatcher`<br>[ObjectWatcher](-object-watcher/index.md) can be passed objects to [watch](-object-watcher/watch.md). It will create [KeyedWeakReference](-keyed-weak-reference/index.md) instances that reference watches objects, and check if those references have been cleared as expected on the [checkRetainedExecutor](#) executor. If not, these objects are considered retained and [ObjectWatcher](-object-watcher/index.md) will then notify the [onObjectRetainedListener](#) on that executor thread. |
| [OnObjectRetainedListener](-on-object-retained-listener/index.md) | `interface OnObjectRetainedListener`<br>Listener used by [ObjectWatcher](-object-watcher/index.md) to report retained objects. |
