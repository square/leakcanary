[leakcanary-android-release](../../index.md) / [leakcanary](../index.md) / [JobContext](./index.md)

# JobContext

`class JobContext`

In memory store that can be used to store objects in a given [HeapAnalysisJob](../-heap-analysis-job/index.md) instance.
This is a simple [MutableMap](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-map/index.html) of [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) to [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), but with unsafe generics access.

By convention, [starter](starter.md) should be the class that triggered the start of the job.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `JobContext(starter: `[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<*>)``JobContext(starter: `[`Class`](https://docs.oracle.com/javase/6/docs/api/java/lang/Class.html)`<*>? = null)`<br>In memory store that can be used to store objects in a given [HeapAnalysisJob](../-heap-analysis-job/index.md) instance. This is a simple [MutableMap](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-map/index.html) of [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) to [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), but with unsafe generics access. |

### Properties

| Name | Summary |
|---|---|
| [starter](starter.md) | `val starter: `[`Class`](https://docs.oracle.com/javase/6/docs/api/java/lang/Class.html)`<*>?` |

### Functions

| Name | Summary |
|---|---|
| [contains](contains.md) | `operator fun contains(key: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [get](get.md) | `operator fun <T> get(key: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`T`](get.md#T)`?` |
| [getOrPut](get-or-put.md) | `fun <T> getOrPut(key: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, defaultValue: () -> `[`T`](get-or-put.md#T)`): `[`T`](get-or-put.md#T) |
| [minusAssign](minus-assign.md) | `operator fun minusAssign(key: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [set](set.md) | `operator fun <T> set(key: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, value: `[`T`](set.md#T)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
