//[shark-graph](../../../index.md)/[shark](../index.md)/[GraphContext](index.md)

# GraphContext

[jvm]\
class [GraphContext](index.md)

In memory store that can be used to store objects in a given [HeapGraph](../-heap-graph/index.md) instance. This is a simple [MutableMap](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-map/index.html) of [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) to [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), but with unsafe generics access.

## Constructors

| | |
|---|---|
| [GraphContext](-graph-context.md) | [jvm]<br>fun [GraphContext](-graph-context.md)() |

## Functions

| Name | Summary |
|---|---|
| [contains](contains.md) | [jvm]<br>operator fun [contains](contains.md)(key: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [get](get.md) | [jvm]<br>operator fun &lt;[T](get.md)&gt; [get](get.md)(key: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)): [T](get.md)? |
| [getOrPut](get-or-put.md) | [jvm]<br>fun &lt;[T](get-or-put.md)&gt; [getOrPut](get-or-put.md)(key: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), defaultValue: () -&gt; [T](get-or-put.md)): [T](get-or-put.md) |
| [minusAssign](minus-assign.md) | [jvm]<br>operator fun [minusAssign](minus-assign.md)(key: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)) |
| [set](set.md) | [jvm]<br>operator fun &lt;[T](set.md)&gt; [set](set.md)(key: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), value: [T](set.md)) |
