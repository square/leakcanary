[shark-graph](../../../index.md) / [shark](../../index.md) / [HeapObject](../index.md) / [HeapClass](./index.md)

# HeapClass

`class HeapClass : `[`HeapObject`](../index.md)

A class in the heap dump.

### Properties

| Name | Summary |
|---|---|
| [classHierarchy](class-hierarchy.md) | `val classHierarchy: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapClass`](./index.md)`>`<br>The class hierarchy starting at this class (included) and ending at the [Object](https://docs.oracle.com/javase/6/docs/api/java/lang/Object.html) class (included). |
| [directInstances](direct-instances.md) | `val directInstances: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapInstance`](../-heap-instance/index.md)`>`<br>All direct instances of this class, ie excluding any instance of subclasses of this class. |
| [graph](graph.md) | `val graph: `[`HeapGraph`](../../-heap-graph/index.md)<br>The graph of objects in the heap, which you can use to navigate the heap. |
| [hasReferenceInstanceFields](has-reference-instance-fields.md) | `val hasReferenceInstanceFields: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [instanceByteSize](instance-byte-size.md) | `val instanceByteSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The total byte size of fields for instances of this class, as registered in the class dump. This includes the size of fields from superclasses. |
| [instances](instances.md) | `val instances: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapInstance`](../-heap-instance/index.md)`>`<br>All instances of this class, including instances of subclasses of this class. |
| [isArrayClass](is-array-class.md) | `val isArrayClass: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if this class is an array class, and false otherwise. |
| [isObjectArrayClass](is-object-array-class.md) | `val isObjectArrayClass: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [isPrimitiveArrayClass](is-primitive-array-class.md) | `val isPrimitiveArrayClass: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html) |
| [isPrimitiveWrapperClass](is-primitive-wrapper-class.md) | `val isPrimitiveWrapperClass: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Whether this is class is a primitive wrapper type |
| [name](name.md) | `val name: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>The name of this class, identical to [Class.getName](https://docs.oracle.com/javase/6/docs/api/java/lang/Class.html#getName()). If this class is an array class, the name has a suffix of brackets for each dimension of the array, e.g. `com.Foo[][]` is a class for 2 dimensional arrays of `com.Foo`. |
| [objectArrayInstances](object-array-instances.md) | `val objectArrayInstances: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapObjectArray`](../-heap-object-array/index.md)`>` |
| [objectId](object-id.md) | `val objectId: `[`Long`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-long/index.html)<br>The heap identifier of this object. |
| [objectIndex](object-index.md) | `val objectIndex: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>An positive object index that's specific to how Shark stores objects in memory. The index starts at 0 and ends at [HeapGraph.objectCount](../../-heap-graph/object-count.md) - 1. There are no gaps, every index value corresponds to an object. Classes are first, then instances, then object arrays then primitive arrays. |
| [primitiveArrayInstances](primitive-array-instances.md) | `val primitiveArrayInstances: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapPrimitiveArray`](../-heap-primitive-array/index.md)`>`<br>Primitive arrays are one dimensional arrays of a primitive type. N-dimension arrays of primitive types (e.g. int[][]) are object arrays pointing to primitive arrays. |
| [recordSize](record-size.md) | `val recordSize: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The total byte size for the record of this object in the heap dump. |
| [simpleName](simple-name.md) | `val simpleName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns [name](name.md) stripped of any string content before the last period (included). |
| [subclasses](subclasses.md) | `val subclasses: `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapObject.HeapClass`](./index.md)`>`<br>All the subclasses (direct and indirect) of this class, in the order they were recorded in the heap dump. |
| [superclass](superclass.md) | `val superclass: `[`HeapObject.HeapClass`](./index.md)`?`<br>The [HeapClass](./index.md) representing the superclass of this [HeapClass](./index.md). If this [HeapClass](./index.md) represents either the [Object](https://docs.oracle.com/javase/6/docs/api/java/lang/Object.html) class or a primitive type, then null is returned. If this [HeapClass](./index.md) represents an array class then the [HeapClass](./index.md) object representing the [Object](https://docs.oracle.com/javase/6/docs/api/java/lang/Object.html) class is returned. |

### Inherited Properties

| Name | Summary |
|---|---|
| [asClass](../as-class.md) | `val asClass: `[`HeapObject.HeapClass`](./index.md)`?`<br>This [HeapObject](../index.md) as a [HeapClass](./index.md) if it is one, or null otherwise |
| [asInstance](../as-instance.md) | `val asInstance: `[`HeapObject.HeapInstance`](../-heap-instance/index.md)`?`<br>This [HeapObject](../index.md) as a [HeapInstance](../-heap-instance/index.md) if it is one, or null otherwise |
| [asObjectArray](../as-object-array.md) | `val asObjectArray: `[`HeapObject.HeapObjectArray`](../-heap-object-array/index.md)`?`<br>This [HeapObject](../index.md) as a [HeapObjectArray](../-heap-object-array/index.md) if it is one, or null otherwise |
| [asPrimitiveArray](../as-primitive-array.md) | `val asPrimitiveArray: `[`HeapObject.HeapPrimitiveArray`](../-heap-primitive-array/index.md)`?`<br>This [HeapObject](../index.md) as a [HeapPrimitiveArray](../-heap-primitive-array/index.md) if it is one, or null otherwise |

### Functions

| Name | Summary |
|---|---|
| [get](get.md) | `operator fun get(fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapField`](../../-heap-field/index.md)`?` |
| [instanceFieldName](instance-field-name.md) | `fun instanceFieldName(fieldRecord: FieldRecord): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Returns the name of the field declared in this class for the specified [fieldRecord](instance-field-name.md#shark.HeapObject.HeapClass$instanceFieldName(shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord)/fieldRecord). |
| [readFieldsByteSize](read-fields-byte-size.md) | `fun readFieldsByteSize(): `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)<br>The total byte size of fields for instances of this class, computed as the sum of the individual size of each field of this class. This does not include the size of fields from superclasses. |
| [readRecord](read-record.md) | `fun readRecord(): ClassDumpRecord`<br>Reads and returns the underlying [ClassDumpRecord](#). |
| [readRecordFields](read-record-fields.md) | `fun readRecordFields(): `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<FieldRecord>` |
| [readRecordStaticFields](read-record-static-fields.md) | `fun readRecordStaticFields(): `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<StaticFieldRecord>` |
| [readStaticField](read-static-field.md) | `fun readStaticField(fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): `[`HeapField`](../../-heap-field/index.md)`?`<br>Returns a [HeapField](../../-heap-field/index.md) object that reflects the specified declared field of the class represented by this [HeapClass](./index.md) object, or null if this field does not exist. The [name](name.md) parameter specifies the simple name of the desired field. |
| [readStaticFields](read-static-fields.md) | `fun readStaticFields(): `[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)`<`[`HeapField`](../../-heap-field/index.md)`>`<br>The static fields of this class, as a sequence of [HeapField](../../-heap-field/index.md). |
| [subclassOf](subclass-of.md) | `infix fun subclassOf(superclass: `[`HeapObject.HeapClass`](./index.md)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if [superclass](subclass-of.md#shark.HeapObject.HeapClass$subclassOf(shark.HeapObject.HeapClass)/superclass) is a superclass of this [HeapClass](./index.md). |
| [superclassOf](superclass-of.md) | `infix fun superclassOf(subclass: `[`HeapObject.HeapClass`](./index.md)`): `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)<br>Returns true if [subclass](superclass-of.md#shark.HeapObject.HeapClass$superclassOf(shark.HeapObject.HeapClass)/subclass) is a sub class of this [HeapClass](./index.md). |
| [toString](to-string.md) | `fun toString(): `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
