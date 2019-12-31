[shark](../../index.md) / [shark](../index.md) / [ObjectReporter](./index.md)

# ObjectReporter

`class ObjectReporter`

Enables [ObjectInspector](../-object-inspector/index.md) implementations to provide insights on [heapObject](heap-object.md), which is
an object (class, instance or array) found in the heap.

A given [ObjectReporter](./index.md) only maps to one object in the heap, but is shared to many
[ObjectInspector](../-object-inspector/index.md) implementations and accumulates insights.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `ObjectReporter(heapObject: HeapObject)`<br>Enables [ObjectInspector](../-object-inspector/index.md) implementations to provide insights on [heapObject](heap-object.md), which is an object (class, instance or array) found in the heap. |

### Properties

| Name | Summary |
|---|---|
| [heapObject](heap-object.md) | `val heapObject: HeapObject` |
| [labels](labels.md) | `val labels: `[`LinkedHashSet`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-linked-hash-set/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>`<br>Labels that will be visible on the corresponding [heapObject](heap-object.md) in the leak trace. |
| [leakingReasons](leaking-reasons.md) | `val leakingReasons: `[`MutableSet`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-set/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>`<br>Reasons for which this object is expected to be unreachable (ie it's leaking). |
| [likelyLeakingReasons](likely-leaking-reasons.md) | `val ~~likelyLeakingReasons~~: `[`MutableSet`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-set/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>`<br>Deprecated, use leakingReasons instead. |
| [notLeakingReasons](not-leaking-reasons.md) | `val notLeakingReasons: `[`MutableSet`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-set/index.html)`<`[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`>`<br>Reasons for which this object is expected to be reachable (ie it's not leaking). |

### Functions

| Name | Summary |
|---|---|
| [whenInstanceOf](when-instance-of.md) | `fun whenInstanceOf(expectedClass: `[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out `[`Any`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html)`>, block: `[`ObjectReporter`](./index.md)`.(HeapInstance) -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Runs [block](when-instance-of.md#shark.ObjectReporter$whenInstanceOf(kotlin.reflect.KClass((kotlin.Any)), kotlin.Function2((shark.ObjectReporter, shark.HeapObject.HeapInstance, kotlin.Unit)))/block) if [ObjectReporter.heapObject](heap-object.md) is an instance of [expectedClass](when-instance-of.md#shark.ObjectReporter$whenInstanceOf(kotlin.reflect.KClass((kotlin.Any)), kotlin.Function2((shark.ObjectReporter, shark.HeapObject.HeapInstance, kotlin.Unit)))/expectedClass).`fun whenInstanceOf(expectedClassName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, block: `[`ObjectReporter`](./index.md)`.(HeapInstance) -> `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)<br>Runs [block](when-instance-of.md#shark.ObjectReporter$whenInstanceOf(kotlin.String, kotlin.Function2((shark.ObjectReporter, shark.HeapObject.HeapInstance, kotlin.Unit)))/block) if [ObjectReporter.heapObject](heap-object.md) is an instance of [expectedClassName](when-instance-of.md#shark.ObjectReporter$whenInstanceOf(kotlin.String, kotlin.Function2((shark.ObjectReporter, shark.HeapObject.HeapInstance, kotlin.Unit)))/expectedClassName). |
