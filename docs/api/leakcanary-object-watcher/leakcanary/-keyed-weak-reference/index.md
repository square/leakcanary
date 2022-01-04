//[leakcanary-object-watcher](../../../index.md)/[leakcanary](../index.md)/[KeyedWeakReference](index.md)

# KeyedWeakReference

[jvm]\
class [KeyedWeakReference](index.md)(referent: [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), key: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), description: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), watchUptimeMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), referenceQueue: [ReferenceQueue](https://docs.oracle.com/javase/8/docs/api/java/lang/ref/ReferenceQueue.html)&lt;[Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)&gt;) : [WeakReference](https://docs.oracle.com/javase/8/docs/api/java/lang/ref/WeakReference.html)&lt;[Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)&gt; 

A weak reference used by [ObjectWatcher](../-object-watcher/index.md) to determine which objects become weakly reachable and which don't. [ObjectWatcher](../-object-watcher/index.md) uses [key](key.md) to keep track of [KeyedWeakReference](index.md) instances that haven't made it into the associated [ReferenceQueue](https://docs.oracle.com/javase/8/docs/api/java/lang/ref/ReferenceQueue.html) yet.

[heapDumpUptimeMillis](-companion/heap-dump-uptime-millis.md) should be set with the current time from [Clock.uptimeMillis](../-clock/uptime-millis.md) right before dumping the heap, so that we can later determine how long an object was retained.

## Constructors

| | |
|---|---|
| [KeyedWeakReference](-keyed-weak-reference.md) | [jvm]<br>fun [KeyedWeakReference](-keyed-weak-reference.md)(referent: [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), key: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), description: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), watchUptimeMillis: [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html), referenceQueue: [ReferenceQueue](https://docs.oracle.com/javase/8/docs/api/java/lang/ref/ReferenceQueue.html)&lt;[Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)&gt;) |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Functions

| Name | Summary |
|---|---|
| [clear](clear.md) | [jvm]<br>open override fun [clear](clear.md)() |
| [enqueue](index.md#-1582683575%2FFunctions%2F986658802) | [jvm]<br>open fun [enqueue](index.md#-1582683575%2FFunctions%2F986658802)(): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [get](index.md#1424066235%2FFunctions%2F986658802) | [jvm]<br>open fun [get](index.md#1424066235%2FFunctions%2F986658802)(): [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)? |
| [isEnqueued](index.md#1222417347%2FFunctions%2F986658802) | [jvm]<br>open fun [isEnqueued](index.md#1222417347%2FFunctions%2F986658802)(): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |

## Properties

| Name | Summary |
|---|---|
| [description](description.md) | [jvm]<br>val [description](description.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [key](key.md) | [jvm]<br>val [key](key.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [next](index.md#-1393079018%2FProperties%2F986658802) | [jvm]<br>val [next](index.md#-1393079018%2FProperties%2F986658802): [Reference](https://docs.oracle.com/javase/8/docs/api/java/lang/ref/Reference.html)&lt;[Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)&gt; |
| [queue](index.md#641515616%2FProperties%2F986658802) | [jvm]<br>val [queue](index.md#641515616%2FProperties%2F986658802): [ReferenceQueue](https://docs.oracle.com/javase/8/docs/api/java/lang/ref/ReferenceQueue.html)&lt;in [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)&gt; |
| [retainedUptimeMillis](retained-uptime-millis.md) | [jvm]<br>@[Volatile](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.jvm/-volatile/index.html)<br>var [retainedUptimeMillis](retained-uptime-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>Time at which the associated object (referent) was considered retained, or -1 if it hasn't been yet. |
| [watchUptimeMillis](watch-uptime-millis.md) | [jvm]<br>val [watchUptimeMillis](watch-uptime-millis.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) |
