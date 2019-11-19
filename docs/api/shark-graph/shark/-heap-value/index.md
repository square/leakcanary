[shark-graph](../../index.md) / [shark](../index.md) / [HeapValue](./index.md)

# HeapValue

`class HeapValue`

Represents a value in the heap dump, which can be an object reference or
a primitive type.

### Constructors

| Name | Summary |
|---|---|
| [&lt;init&gt;](-init-.md) | `HeapValue(graph: `[`HeapGraph`](../-heap-graph/index.md)`, holder: ValueHolder)`<br>Represents a value in the heap dump, which can be an object reference or a primitive type. |

### Properties

| Name | Summary |
|---|---|
| [asBoolean](as-boolean.md) | `val asBoolean: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`?`<br>This [HeapValue](./index.md) as a [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) if it represents one, or null otherwise. |
| [asByte](as-byte.md) | `val asByte: `[`Byte`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte/index.html)`?`<br>This [HeapValue](./index.md) as a [Byte](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-byte/index.html) if it represents one, or null otherwise. |
| [asChar](as-char.md) | `val asChar: `[`Char`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-char/index.html)`?`<br>This [HeapValue](./index.md) as a [Char](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-char/index.html) if it represents one, or null otherwise. |
| [asDouble](as-double.md) | `val asDouble: `[`Double`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double/index.html)`?`<br>This [HeapValue](./index.md) as a [Double](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-double/index.html) if it represents one, or null otherwise. |
| [asFloat](as-float.md) | `val asFloat: `[`Float`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-float/index.html)`?`<br>This [HeapValue](./index.md) as a [Float](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-float/index.html) if it represents one, or null otherwise. |
| [asInt](as-int.md) | `val asInt: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)`?`<br>This [HeapValue](./index.md) as an [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) if it represents one, or null otherwise. |
| [asLong](as-long.md) | `val asLong: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`?`<br>This [HeapValue](./index.md) as a [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) if it represents one, or null otherwise. |
| [asNonNullObjectId](as-non-null-object-id.md) | `val asNonNullObjectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`?`<br>This [HeapValue](./index.md) as a [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) if it represents a non null object reference, or null otherwise. |
| [asObject](as-object.md) | `val asObject: `[`HeapObject`](../-heap-object/index.md)`?`<br>The [HeapObject](../-heap-object/index.md) referenced by this [HeapValue](./index.md) if it represents a non null object reference, or null otherwise. |
| [asObjectId](as-object-id.md) | `val asObjectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)`?`<br>This [HeapValue](./index.md) as a [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html) if it represents an object reference, or null otherwise. |
| [asShort](as-short.md) | `val asShort: `[`Short`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-short/index.html)`?`<br>This [HeapValue](./index.md) as a [Short](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-short/index.html) if it represents one, or null otherwise. |
| [graph](graph.md) | `val graph: `[`HeapGraph`](../-heap-graph/index.md)<br>The graph of objects in the heap, which you can use to navigate the heap. |
| [holder](holder.md) | `val holder: ValueHolder`<br>Holds the actual value that this [HeapValue](./index.md) represents. |
| [isNonNullReference](is-non-null-reference.md) | `val isNonNullReference: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>True is this [HeapValue](./index.md) represents a non null object reference, false otherwise. |
| [isNullReference](is-null-reference.md) | `val isNullReference: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>True is this [HeapValue](./index.md) represents a null object reference, false otherwise. |

### Functions

| Name | Summary |
|---|---|
| [readAsJavaString](read-as-java-string.md) | `fun readAsJavaString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`?`<br>If this [HeapValue](./index.md) if it represents a non null object reference to an instance of the [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) class that exists in the heap dump, returns a [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) instance with content that matches the string in the heap dump. Otherwise returns null. |
