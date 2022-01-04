//[leakcanary-object-watcher](../../../index.md)/[leakcanary](../index.md)/[ObjectWatcher](index.md)

# ObjectWatcher

[jvm]\
class [ObjectWatcher](index.md)(clock: [Clock](../-clock/index.md), checkRetainedExecutor: [Executor](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/Executor.html), isEnabled: () -&gt; [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)) : [ReachabilityWatcher](../-reachability-watcher/index.md)

[ObjectWatcher](index.md) can be passed objects to watch. It will create [KeyedWeakReference](../-keyed-weak-reference/index.md) instances that reference watches objects, and check if those references have been cleared as expected on the checkRetainedExecutor executor. If not, these objects are considered retained and [ObjectWatcher](index.md) will then notify registered [OnObjectRetainedListener](../-on-object-retained-listener/index.md)s on that executor thread.

checkRetainedExecutor is expected to run its tasks on a background thread, with a significant delay to give the GC the opportunity to identify weakly reachable objects.

[ObjectWatcher](index.md) is thread safe.

## Constructors

| | |
|---|---|
| [ObjectWatcher](-object-watcher.md) | [jvm]<br>fun [ObjectWatcher](-object-watcher.md)(clock: [Clock](../-clock/index.md), checkRetainedExecutor: [Executor](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/Executor.html), isEnabled: () -&gt; [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) = { true }) |

## Functions

| Name | Summary |
|---|---|
| [addOnObjectRetainedListener](add-on-object-retained-listener.md) | [jvm]<br>@[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)<br>fun [addOnObjectRetainedListener](add-on-object-retained-listener.md)(listener: [OnObjectRetainedListener](../-on-object-retained-listener/index.md)) |
| [clearObjectsWatchedBefore](clear-objects-watched-before.md) | [jvm]<br>@[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)<br>fun [clearObjectsWatchedBefore](clear-objects-watched-before.md)(heapDumpUptimeMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html))<br>Clears all [KeyedWeakReference](../-keyed-weak-reference/index.md) that were created before [heapDumpUptimeMillis](clear-objects-watched-before.md) (based on [clock](../-clock/uptime-millis.md)) |
| [clearWatchedObjects](clear-watched-objects.md) | [jvm]<br>@[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)<br>fun [clearWatchedObjects](clear-watched-objects.md)()<br>Clears all [KeyedWeakReference](../-keyed-weak-reference/index.md) |
| [expectWeaklyReachable](expect-weakly-reachable.md) | [jvm]<br>@[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)<br>open override fun [expectWeaklyReachable](expect-weakly-reachable.md)(watchedObject: [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), description: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html))<br>Expects the provided [watchedObject](expect-weakly-reachable.md) to become weakly reachable soon. If not, [watchedObject](expect-weakly-reachable.md) will be considered retained. |
| [removeOnObjectRetainedListener](remove-on-object-retained-listener.md) | [jvm]<br>@[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)<br>fun [removeOnObjectRetainedListener](remove-on-object-retained-listener.md)(listener: [OnObjectRetainedListener](../-on-object-retained-listener/index.md)) |

## Properties

| Name | Summary |
|---|---|
| [hasRetainedObjects](has-retained-objects.md) | [jvm]<br>@get:[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)<br>val [hasRetainedObjects](has-retained-objects.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if there are watched objects that aren't weakly reachable, and have been watched for long enough to be considered retained. |
| [hasWatchedObjects](has-watched-objects.md) | [jvm]<br>@get:[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)<br>val [hasWatchedObjects](has-watched-objects.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if there are watched objects that aren't weakly reachable, even if they haven't been watched for long enough to be considered retained. |
| [retainedObjectCount](retained-object-count.md) | [jvm]<br>@get:[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)<br>val [retainedObjectCount](retained-object-count.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>Returns the number of retained objects, ie the number of watched objects that aren't weakly reachable, and have been watched for long enough to be considered retained. |
| [retainedObjects](retained-objects.md) | [jvm]<br>@get:[Synchronized](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-synchronized/index.html)<br>val [retainedObjects](retained-objects.md): [List](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)&lt;[Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)&gt;<br>Returns the objects that are currently considered retained. Useful for logging purposes. Be careful with those objects and release them ASAP as you may creating longer lived leaks then the one that are already there. |
