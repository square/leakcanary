[shark-graph](../index.md) / [shark](./index.md)

## Package shark

### Types

| Name | Summary |
|---|---|
| [GraphContext](-graph-context/index.md) | `class GraphContext`<br>In memory store that can be used to store objects in a given [HeapGraph](-heap-graph/index.md) instance. This is a simple [MutableMap](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-map/index.html) of [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) to [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), but with unsafe generics access. |
| [HeapField](-heap-field/index.md) | `class HeapField`<br>Represents a static field or an instance field. |
| [HeapGraph](-heap-graph/index.md) | `interface HeapGraph`<br>Enables navigation through the heap graph of objects. |
| [HeapObject](-heap-object/index.md) | `sealed class HeapObject`<br>An object in the heap dump. |
| [HeapValue](-heap-value/index.md) | `class HeapValue`<br>Represents a value in the heap dump, which can be an object reference or a primitive type. |
| [HprofHeapGraph](-hprof-heap-graph/index.md) | `class HprofHeapGraph : `[`HeapGraph`](-heap-graph/index.md)<br>A [HeapGraph](-heap-graph/index.md) that reads from an indexed [Hprof](#). Create a new instance with [indexHprof](-hprof-heap-graph/index-hprof.md). |
| [ProguardMapping](-proguard-mapping/index.md) | `class ProguardMapping` |
| [ProguardMappingReader](-proguard-mapping-reader/index.md) | `class ProguardMappingReader` |
