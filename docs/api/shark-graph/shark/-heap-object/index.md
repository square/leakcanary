//[shark-graph](../../../index.md)/[shark](../index.md)/[HeapObject](index.md)

# HeapObject

[jvm]\
sealed class [HeapObject](index.md)

An object in the heap dump.

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |
| [HeapClass](-heap-class/index.md) | [jvm]<br>class [HeapClass](-heap-class/index.md) : [HeapObject](index.md)<br>A class in the heap dump. |
| [HeapInstance](-heap-instance/index.md) | [jvm]<br>class [HeapInstance](-heap-instance/index.md) : [HeapObject](index.md)<br>An instance in the heap dump. |
| [HeapObjectArray](-heap-object-array/index.md) | [jvm]<br>class [HeapObjectArray](-heap-object-array/index.md) : [HeapObject](index.md)<br>An object array in the heap dump. |
| [HeapPrimitiveArray](-heap-primitive-array/index.md) | [jvm]<br>class [HeapPrimitiveArray](-heap-primitive-array/index.md) : [HeapObject](index.md)<br>A primitive array in the heap dump. |

## Functions

| Name | Summary |
|---|---|
| [readRecord](read-record.md) | [jvm]<br>abstract fun [readRecord](read-record.md)(): HprofRecord.HeapDumpRecord.ObjectRecord<br>Reads and returns the underlying ObjectRecord. |

## Properties

| Name | Summary |
|---|---|
| [asClass](as-class.md) | [jvm]<br>val [asClass](as-class.md): [HeapObject.HeapClass](-heap-class/index.md)?<br>This [HeapObject](index.md) as a [HeapClass](-heap-class/index.md) if it is one, or null otherwise |
| [asInstance](as-instance.md) | [jvm]<br>val [asInstance](as-instance.md): [HeapObject.HeapInstance](-heap-instance/index.md)?<br>This [HeapObject](index.md) as a [HeapInstance](-heap-instance/index.md) if it is one, or null otherwise |
| [asObjectArray](as-object-array.md) | [jvm]<br>val [asObjectArray](as-object-array.md): [HeapObject.HeapObjectArray](-heap-object-array/index.md)?<br>This [HeapObject](index.md) as a [HeapObjectArray](-heap-object-array/index.md) if it is one, or null otherwise |
| [asPrimitiveArray](as-primitive-array.md) | [jvm]<br>val [asPrimitiveArray](as-primitive-array.md): [HeapObject.HeapPrimitiveArray](-heap-primitive-array/index.md)?<br>This [HeapObject](index.md) as a [HeapPrimitiveArray](-heap-primitive-array/index.md) if it is one, or null otherwise |
| [graph](graph.md) | [jvm]<br>abstract val [graph](graph.md): [HeapGraph](../-heap-graph/index.md)<br>The graph of objects in the heap, which you can use to navigate the heap. |
| [objectId](object-id.md) | [jvm]<br>abstract val [objectId](object-id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The heap identifier of this object. |
| [objectIndex](object-index.md) | [jvm]<br>abstract val [objectIndex](object-index.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>An positive object index that's specific to how Shark stores objects in memory. The index starts at 0 and ends at [HeapGraph.objectCount](../-heap-graph/object-count.md) - 1. There are no gaps, every index value corresponds to an object. Classes are first, then instances, then object arrays then primitive arrays. |
| [recordSize](record-size.md) | [jvm]<br>abstract val [recordSize](record-size.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The total byte size for the record of this object in the heap dump. |

## Inheritors

| Name |
|---|
| [HeapClass](-heap-class/index.md) |
| [HeapInstance](-heap-instance/index.md) |
| [HeapObjectArray](-heap-object-array/index.md) |
| [HeapPrimitiveArray](-heap-primitive-array/index.md) |
