//[shark](../../../index.md)/[shark](../index.md)/[ObjectReporter](index.md)

# ObjectReporter

[jvm]\
class [ObjectReporter](index.md)(heapObject: HeapObject)

Enables [ObjectInspector](../-object-inspector/index.md) implementations to provide insights on [heapObject](heap-object.md), which is an object (class, instance or array) found in the heap.

A given [ObjectReporter](index.md) only maps to one object in the heap, but is shared to many [ObjectInspector](../-object-inspector/index.md) implementations and accumulates insights.

## Constructors

| | |
|---|---|
| [ObjectReporter](-object-reporter.md) | [jvm]<br>fun [ObjectReporter](-object-reporter.md)(heapObject: HeapObject) |

## Functions

| Name | Summary |
|---|---|
| [whenInstanceOf](when-instance-of.md) | [jvm]<br>fun [whenInstanceOf](when-instance-of.md)(expectedClassName: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), block: [ObjectReporter](index.md).(HeapObject.HeapInstance) -&gt; [Unit](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html))<br>Runs [block](when-instance-of.md) if [ObjectReporter.heapObject](heap-object.md) is an instance of [expectedClassName](when-instance-of.md).<br>[jvm]<br>fun [whenInstanceOf](when-instance-of.md)(expectedClass: [KClass](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)&lt;out [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)&gt;, block: [ObjectReporter](index.md).(HeapObject.HeapInstance) -&gt; [Unit](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html))<br>Runs [block](when-instance-of.md) if [ObjectReporter.heapObject](heap-object.md) is an instance of [expectedClass](when-instance-of.md). |

## Properties

| Name | Summary |
|---|---|
| [heapObject](heap-object.md) | [jvm]<br>val [heapObject](heap-object.md): HeapObject |
| [labels](labels.md) | [jvm]<br>val [labels](labels.md): [LinkedHashSet](https://docs.oracle.com/javase/8/docs/api/java/util/LinkedHashSet.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt;<br>Labels that will be visible on the corresponding [heapObject](heap-object.md) in the leak trace. |
| [leakingReasons](leaking-reasons.md) | [jvm]<br>val [leakingReasons](leaking-reasons.md): [MutableSet](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-set/index.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt;<br>Reasons for which this object is expected to be unreachable (ie it's leaking). |
| [notLeakingReasons](not-leaking-reasons.md) | [jvm]<br>val [notLeakingReasons](not-leaking-reasons.md): [MutableSet](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-set/index.html)&lt;[String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)&gt;<br>Reasons for which this object is expected to be reachable (ie it's not leaking). |
