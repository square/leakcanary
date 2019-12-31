[shark-graph](../../../index.md) / [shark](../../index.md) / [HeapObject](../index.md) / [HeapPrimitiveArray](./index.md)

# HeapPrimitiveArray

`class HeapPrimitiveArray : `[`HeapObject`](../index.md)

A primitive array in the heap dump.

### Properties

| Name | Summary |
|---|---|
| [arrayClass](array-class.md) | `val arrayClass: `[`HeapObject.HeapClass`](../-heap-class/index.md)<br>The class of this array. |
| [arrayClassName](array-class-name.md) | `val arrayClassName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>The name of the class of this array, identical to [Class.getName](https://docs.oracle.com/javase/6/docs/api/java/lang/Class.html#getName()). |
| [graph](graph.md) | `val graph: `[`HeapGraph`](../../-heap-graph/index.md)<br>The graph of objects in the heap, which you can use to navigate the heap. |
| [objectId](object-id.md) | `val objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The heap identifier of this object. |
| [primitiveType](primitive-type.md) | `val primitiveType: PrimitiveType`<br>The [PrimitiveType](#) of elements in this array. |

### Inherited Properties

| Name | Summary |
|---|---|
| [asClass](../as-class.md) | `val asClass: `[`HeapObject.HeapClass`](../-heap-class/index.md)`?`<br>This [HeapObject](../index.md) as a [HeapClass](../-heap-class/index.md) if it is one, or null otherwise |
| [asInstance](../as-instance.md) | `val asInstance: `[`HeapObject.HeapInstance`](../-heap-instance/index.md)`?`<br>This [HeapObject](../index.md) as a [HeapInstance](../-heap-instance/index.md) if it is one, or null otherwise |
| [asObjectArray](../as-object-array.md) | `val asObjectArray: `[`HeapObject.HeapObjectArray`](../-heap-object-array/index.md)`?`<br>This [HeapObject](../index.md) as a [HeapObjectArray](../-heap-object-array/index.md) if it is one, or null otherwise |
| [asPrimitiveArray](../as-primitive-array.md) | `val asPrimitiveArray: `[`HeapObject.HeapPrimitiveArray`](./index.md)`?`<br>This [HeapObject](../index.md) as a [HeapPrimitiveArray](./index.md) if it is one, or null otherwise |

### Functions

| Name | Summary |
|---|---|
| [readByteSize](read-byte-size.md) | `fun readByteSize(): `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The total byte shallow size of elements in this array. |
| [readRecord](read-record.md) | `fun readRecord(): PrimitiveArrayDumpRecord`<br>Reads and returns the underlying [PrimitiveArrayDumpRecord](#). |
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
