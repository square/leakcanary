//[shark-graph](../../../../index.md)/[shark](../../index.md)/[HeapObject](../index.md)/[HeapPrimitiveArray](index.md)

# HeapPrimitiveArray

[jvm]\
class [HeapPrimitiveArray](index.md) : [HeapObject](../index.md)

A primitive array in the heap dump.

## Functions

| Name | Summary |
|---|---|
| [readByteSize](read-byte-size.md) | [jvm]<br>fun [readByteSize](read-byte-size.md)(): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The total byte shallow size of elements in this array. |
| [readRecord](read-record.md) | [jvm]<br>open override fun [readRecord](read-record.md)(): HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord<br>Reads and returns the underlying PrimitiveArrayDumpRecord. |
| [toString](to-string.md) | [jvm]<br>open override fun [toString](to-string.md)(): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

## Properties

| Name | Summary |
|---|---|
| [arrayClass](array-class.md) | [jvm]<br>val [arrayClass](array-class.md): [HeapObject.HeapClass](../-heap-class/index.md)<br>The class of this array. |
| [arrayClassName](array-class-name.md) | [jvm]<br>val [arrayClassName](array-class-name.md): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>The name of the class of this array, identical to [Class.getName](https://docs.oracle.com/javase/8/docs/api/java/lang/Class.html#getName--). |
| [asClass](../as-class.md) | [jvm]<br>val [asClass](../as-class.md): [HeapObject.HeapClass](../-heap-class/index.md)?<br>This [HeapObject](../index.md) as a [HeapClass](../-heap-class/index.md) if it is one, or null otherwise |
| [asInstance](../as-instance.md) | [jvm]<br>val [asInstance](../as-instance.md): [HeapObject.HeapInstance](../-heap-instance/index.md)?<br>This [HeapObject](../index.md) as a [HeapInstance](../-heap-instance/index.md) if it is one, or null otherwise |
| [asObjectArray](../as-object-array.md) | [jvm]<br>val [asObjectArray](../as-object-array.md): [HeapObject.HeapObjectArray](../-heap-object-array/index.md)?<br>This [HeapObject](../index.md) as a [HeapObjectArray](../-heap-object-array/index.md) if it is one, or null otherwise |
| [asPrimitiveArray](../as-primitive-array.md) | [jvm]<br>val [asPrimitiveArray](../as-primitive-array.md): [HeapObject.HeapPrimitiveArray](index.md)?<br>This [HeapObject](../index.md) as a [HeapPrimitiveArray](index.md) if it is one, or null otherwise |
| [graph](graph.md) | [jvm]<br>open override val [graph](graph.md): [HeapGraph](../../-heap-graph/index.md)<br>The graph of objects in the heap, which you can use to navigate the heap. |
| [objectId](object-id.md) | [jvm]<br>open override val [objectId](object-id.md): [Long](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The heap identifier of this object. |
| [objectIndex](object-index.md) | [jvm]<br>open override val [objectIndex](object-index.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>An positive object index that's specific to how Shark stores objects in memory. The index starts at 0 and ends at [HeapGraph.objectCount](../../-heap-graph/object-count.md) - 1. There are no gaps, every index value corresponds to an object. Classes are first, then instances, then object arrays then primitive arrays. |
| [primitiveType](primitive-type.md) | [jvm]<br>val [primitiveType](primitive-type.md): PrimitiveType<br>The PrimitiveType of elements in this array. |
| [recordSize](record-size.md) | [jvm]<br>open override val [recordSize](record-size.md): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The total byte size for the record of this object in the heap dump. |
