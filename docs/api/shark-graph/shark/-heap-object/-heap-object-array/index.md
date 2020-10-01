[shark-graph](../../../index.md) / [shark](../../index.md) / [HeapObject](../index.md) / [HeapObjectArray](./index.md)

# HeapObjectArray

`class HeapObjectArray : `[`HeapObject`](../index.md)

An object array in the heap dump.

### Properties

| Name | Summary |
|---|---|
| [arrayClass](array-class.md) | `val arrayClass: `[`HeapObject.HeapClass`](../-heap-class/index.md)<br>The class of this array. |
| [arrayClassName](array-class-name.md) | `val arrayClassName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>The name of the class of this array, identical to [Class.getName](https://docs.oracle.com/javase/6/docs/api/java/lang/Class.html#getName()). |
| [arrayClassSimpleName](array-class-simple-name.md) | `val arrayClassSimpleName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns [arrayClassName](array-class-name.md) stripped of any string content before the last period (included). |
| [graph](graph.md) | `val graph: `[`HeapGraph`](../../-heap-graph/index.md)<br>The graph of objects in the heap, which you can use to navigate the heap. |
| [objectId](object-id.md) | `val objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The heap identifier of this object. |
| [objectIndex](object-index.md) | `val objectIndex: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>An positive object index that's specific to how Shark stores objects in memory. The index starts at 0 and ends at [HeapGraph.objectCount](../../-heap-graph/object-count.md) - 1. There are no gaps, every index value corresponds to an object. Classes are first, then instances, then object arrays then primitive arrays. |
| [recordSize](record-size.md) | `val recordSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The total byte size for the record of this object in the heap dump. |

### Inherited Properties

| Name | Summary |
|---|---|
| [asClass](../as-class.md) | `val asClass: `[`HeapObject.HeapClass`](../-heap-class/index.md)`?`<br>This [HeapObject](../index.md) as a [HeapClass](../-heap-class/index.md) if it is one, or null otherwise |
| [asInstance](../as-instance.md) | `val asInstance: `[`HeapObject.HeapInstance`](../-heap-instance/index.md)`?`<br>This [HeapObject](../index.md) as a [HeapInstance](../-heap-instance/index.md) if it is one, or null otherwise |
| [asObjectArray](../as-object-array.md) | `val asObjectArray: `[`HeapObject.HeapObjectArray`](./index.md)`?`<br>This [HeapObject](../index.md) as a [HeapObjectArray](./index.md) if it is one, or null otherwise |
| [asPrimitiveArray](../as-primitive-array.md) | `val asPrimitiveArray: `[`HeapObject.HeapPrimitiveArray`](../-heap-primitive-array/index.md)`?`<br>This [HeapObject](../index.md) as a [HeapPrimitiveArray](../-heap-primitive-array/index.md) if it is one, or null otherwise |

### Functions

| Name | Summary |
|---|---|
| [readByteSize](read-byte-size.md) | `fun readByteSize(): `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The total byte shallow size of elements in this array. |
| [readElements](read-elements.md) | `fun readElements(): `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapValue`](../../-heap-value/index.md)`>`<br>The elements in this array, as a sequence of [HeapValue](../../-heap-value/index.md). |
| [readRecord](read-record.md) | `fun readRecord(): ObjectArrayDumpRecord`<br>Reads and returns the underlying [ObjectArrayDumpRecord](#). |
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
