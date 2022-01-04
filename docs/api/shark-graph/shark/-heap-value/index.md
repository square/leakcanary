//[shark-graph](../../../index.md)/[shark](../index.md)/[HeapValue](index.md)

# HeapValue

[jvm]\
class [HeapValue](index.md)(graph: [HeapGraph](../-heap-graph/index.md), holder: ValueHolder)

Represents a value in the heap dump, which can be an object reference or a primitive type.

## Constructors

| | |
|---|---|
| [HeapValue](-heap-value.md) | [jvm]<br>fun [HeapValue](-heap-value.md)(graph: [HeapGraph](../-heap-graph/index.md), holder: ValueHolder) |

## Functions

| Name | Summary |
|---|---|
| [readAsJavaString](read-as-java-string.md) | [jvm]<br>fun [readAsJavaString](read-as-java-string.md)(): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)?<br>If this [HeapValue](index.md) if it represents a non null object reference to an instance of the [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) class that exists in the heap dump, returns a [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) instance with content that matches the string in the heap dump. Otherwise returns null. |

## Properties

| Name | Summary |
|---|---|
| [asBoolean](as-boolean.md) | [jvm]<br>val [asBoolean](as-boolean.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)?<br>This [HeapValue](index.md) as a [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) if it represents one, or null otherwise. |
| [asByte](as-byte.md) | [jvm]<br>val [asByte](as-byte.md): [Byte](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte/index.html)?<br>This [HeapValue](index.md) as a [Byte](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte/index.html) if it represents one, or null otherwise. |
| [asChar](as-char.md) | [jvm]<br>val [asChar](as-char.md): [Char](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-char/index.html)?<br>This [HeapValue](index.md) as a [Char](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-char/index.html) if it represents one, or null otherwise. |
| [asDouble](as-double.md) | [jvm]<br>val [asDouble](as-double.md): [Double](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double/index.html)?<br>This [HeapValue](index.md) as a [Double](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double/index.html) if it represents one, or null otherwise. |
| [asFloat](as-float.md) | [jvm]<br>val [asFloat](as-float.md): [Float](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-float/index.html)?<br>This [HeapValue](index.md) as a [Float](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-float/index.html) if it represents one, or null otherwise. |
| [asInt](as-int.md) | [jvm]<br>val [asInt](as-int.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)?<br>This [HeapValue](index.md) as an [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) if it represents one, or null otherwise. |
| [asLong](as-long.md) | [jvm]<br>val [asLong](as-long.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)?<br>This [HeapValue](index.md) as a [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) if it represents one, or null otherwise. |
| [asNonNullObjectId](as-non-null-object-id.md) | [jvm]<br>val [asNonNullObjectId](as-non-null-object-id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)?<br>This [HeapValue](index.md) as a [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) if it represents a non null object reference, or null otherwise. |
| [asObject](as-object.md) | [jvm]<br>val [asObject](as-object.md): [HeapObject](../-heap-object/index.md)?<br>The [HeapObject](../-heap-object/index.md) referenced by this [HeapValue](index.md) if it represents a non null object reference, or null otherwise. |
| [asObjectId](as-object-id.md) | [jvm]<br>val [asObjectId](as-object-id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)?<br>This [HeapValue](index.md) as a [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) if it represents an object reference, or null otherwise. |
| [asShort](as-short.md) | [jvm]<br>val [asShort](as-short.md): [Short](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-short/index.html)?<br>This [HeapValue](index.md) as a [Short](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-short/index.html) if it represents one, or null otherwise. |
| [graph](graph.md) | [jvm]<br>val [graph](graph.md): [HeapGraph](../-heap-graph/index.md)<br>The graph of objects in the heap, which you can use to navigate the heap. |
| [holder](holder.md) | [jvm]<br>val [holder](holder.md): ValueHolder<br>Holds the actual value that this [HeapValue](index.md) represents. |
| [isNonNullReference](is-non-null-reference.md) | [jvm]<br>val [isNonNullReference](is-non-null-reference.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>True is this [HeapValue](index.md) represents a non null object reference, false otherwise. |
| [isNullReference](is-null-reference.md) | [jvm]<br>val [isNullReference](is-null-reference.md): [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>True is this [HeapValue](index.md) represents a null object reference, false otherwise. |
