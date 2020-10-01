[shark-graph](../../index.md) / [shark](../index.md) / [HeapObject](./index.md)

# HeapObject

`sealed class HeapObject`

An object in the heap dump.

### Types

| Name | Summary |
|---|---|
| [HeapClass](-heap-class/index.md) | `class HeapClass : `[`HeapObject`](./index.md)<br>A class in the heap dump. |
| [HeapInstance](-heap-instance/index.md) | `class HeapInstance : `[`HeapObject`](./index.md)<br>An instance in the heap dump. |
| [HeapObjectArray](-heap-object-array/index.md) | `class HeapObjectArray : `[`HeapObject`](./index.md)<br>An object array in the heap dump. |
| [HeapPrimitiveArray](-heap-primitive-array/index.md) | `class HeapPrimitiveArray : `[`HeapObject`](./index.md)<br>A primitive array in the heap dump. |

### Properties

| Name | Summary |
|---|---|
| [asClass](as-class.md) | `val asClass: `[`HeapObject.HeapClass`](-heap-class/index.md)`?`<br>This [HeapObject](./index.md) as a [HeapClass](-heap-class/index.md) if it is one, or null otherwise |
| [asInstance](as-instance.md) | `val asInstance: `[`HeapObject.HeapInstance`](-heap-instance/index.md)`?`<br>This [HeapObject](./index.md) as a [HeapInstance](-heap-instance/index.md) if it is one, or null otherwise |
| [asObjectArray](as-object-array.md) | `val asObjectArray: `[`HeapObject.HeapObjectArray`](-heap-object-array/index.md)`?`<br>This [HeapObject](./index.md) as a [HeapObjectArray](-heap-object-array/index.md) if it is one, or null otherwise |
| [asPrimitiveArray](as-primitive-array.md) | `val asPrimitiveArray: `[`HeapObject.HeapPrimitiveArray`](-heap-primitive-array/index.md)`?`<br>This [HeapObject](./index.md) as a [HeapPrimitiveArray](-heap-primitive-array/index.md) if it is one, or null otherwise |
| [graph](graph.md) | `abstract val graph: `[`HeapGraph`](../-heap-graph/index.md)<br>The graph of objects in the heap, which you can use to navigate the heap. |
| [objectId](object-id.md) | `abstract val objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The heap identifier of this object. |
| [objectIndex](object-index.md) | `abstract val objectIndex: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>An positive object index that's specific to how Shark stores objects in memory. The index starts at 0 and ends at [HeapGraph.objectCount](../-heap-graph/object-count.md) - 1. There are no gaps, every index value corresponds to an object. Classes are first, then instances, then object arrays then primitive arrays. |
| [recordSize](record-size.md) | `abstract val recordSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The total byte size for the record of this object in the heap dump. |

### Functions

| Name | Summary |
|---|---|
| [readRecord](read-record.md) | `abstract fun readRecord(): ObjectRecord`<br>Reads and returns the underlying [ObjectRecord](#). |

### Inheritors

| Name | Summary |
|---|---|
| [HeapClass](-heap-class/index.md) | `class HeapClass : `[`HeapObject`](./index.md)<br>A class in the heap dump. |
| [HeapInstance](-heap-instance/index.md) | `class HeapInstance : `[`HeapObject`](./index.md)<br>An instance in the heap dump. |
| [HeapObjectArray](-heap-object-array/index.md) | `class HeapObjectArray : `[`HeapObject`](./index.md)<br>An object array in the heap dump. |
| [HeapPrimitiveArray](-heap-primitive-array/index.md) | `class HeapPrimitiveArray : `[`HeapObject`](./index.md)<br>A primitive array in the heap dump. |
