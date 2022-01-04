//[shark-graph](../../index.md)/[shark](index.md)

# Package shark

## Types

| Name | Summary |
|---|---|
| [CloseableHeapGraph](-closeable-heap-graph/index.md) | [jvm]<br>interface [CloseableHeapGraph](-closeable-heap-graph/index.md) : [HeapGraph](-heap-graph/index.md), [Closeable](https://docs.oracle.com/javase/8/docs/api/java/io/Closeable.html)<br>A [HeapGraph](-heap-graph/index.md) that should be closed after being used. |
| [GraphContext](-graph-context/index.md) | [jvm]<br>class [GraphContext](-graph-context/index.md)<br>In memory store that can be used to store objects in a given [HeapGraph](-heap-graph/index.md) instance. This is a simple [MutableMap](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-map/index.html) of [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) to [Any](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-any/index.html), but with unsafe generics access. |
| [HeapField](-heap-field/index.md) | [jvm]<br>class [HeapField](-heap-field/index.md)(declaringClass: [HeapObject.HeapClass](-heap-object/-heap-class/index.md), name: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html), value: [HeapValue](-heap-value/index.md))<br>Represents a static field or an instance field. |
| [HeapGraph](-heap-graph/index.md) | [jvm]<br>interface [HeapGraph](-heap-graph/index.md)<br>Enables navigation through the heap graph of objects. |
| [HeapObject](-heap-object/index.md) | [jvm]<br>sealed class [HeapObject](-heap-object/index.md)<br>An object in the heap dump. |
| [HeapValue](-heap-value/index.md) | [jvm]<br>class [HeapValue](-heap-value/index.md)(graph: [HeapGraph](-heap-graph/index.md), holder: ValueHolder)<br>Represents a value in the heap dump, which can be an object reference or a primitive type. |
| [HprofHeapGraph](-hprof-heap-graph/index.md) | [jvm]<br>class [HprofHeapGraph](-hprof-heap-graph/index.md) : [CloseableHeapGraph](-closeable-heap-graph/index.md)<br>A [HeapGraph](-heap-graph/index.md) that reads from an Hprof file indexed by [HprofIndex](-hprof-index/index.md). |
| [HprofIndex](-hprof-index/index.md) | [jvm]<br>class [HprofIndex](-hprof-index/index.md)<br>An index on a Hprof file. See [openHeapGraph](-hprof-index/open-heap-graph.md). |
