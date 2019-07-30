[shark-graph](../../index.md) / [shark](../index.md) / [GraphContext](./index.md)

# GraphContext

`class GraphContext`

In memory store that can be used to store objects in a given [HeapGraph](../-heap-graph/index.md) instance.
This is a simple [MutableMap](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-map/index.html) of [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) to [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), but with unsafe generics access.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `GraphContext()`<br>In memory store that can be used to store objects in a given [HeapGraph](../-heap-graph/index.md) instance. This is a simple [MutableMap](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-map/index.html) of [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) to [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), but with unsafe generics access. |

### Functions

| Name | Summary |
|---|---|
| [contains](contains.md) | `operator fun contains(key: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [get](get.md) | `operator fun <T> get(key: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`T`](get.md#T)`?` |
| [getOrPut](get-or-put.md) | `fun <T> getOrPut(key: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, defaultValue: () -> `[`T`](get-or-put.md#T)`): `[`T`](get-or-put.md#T) |
| [minusAssign](minus-assign.md) | `operator fun minusAssign(key: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
| [set](set.md) | `operator fun <T> set(key: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, value: `[`T`](set.md#T)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
